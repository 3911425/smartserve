package cn.smartserve.agent;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.*;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.*;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Service;

@Service
@EnableScheduling
public class RunDispatcher {

  private final RunJournal journal;
  private final AgentOrchestrator graph;
  private final RabbitTemplate rabbit;
  private final TraceExporter traces;

  @Value("${smartserve.async-mode}")
  String mode;

  @Value("${smartserve.run-queue}")
  String queue;

  @Value("${WORKER_ENABLED:true}")
  boolean worker;

  private volatile boolean ready;
  private final ConcurrentHashMap<String, String> active =
    new ConcurrentHashMap<>();
  private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
    4,
    4,
    30,
    TimeUnit.SECONDS,
    new ArrayBlockingQueue<>(64),
    r -> {
      Thread t = new Thread(r, "agent-worker");
      t.setDaemon(true);
      return t;
    }
  );

  public RunDispatcher(
    RunJournal journal,
    AgentOrchestrator graph,
    RabbitTemplate rabbit,
    TraceExporter traces
  ) {
    this.journal = journal;
    this.graph = graph;
    this.rabbit = rabbit;
    this.traces = traces;
  }

  @jakarta.annotation.PreDestroy
  public void stop() {
    ready = false;
    executor.shutdownNow();
  }

  @EventListener(ApplicationReadyEvent.class)
  public void ready() {
    ready = true;
  }

  public void submit(String id) {
    if (mode.equals("rabbit")) {
      try {
        rabbit.convertAndSend(queue, id);
      } catch (Exception e) {
        journal.event(
          id,
          "queue_retry",
          java.util.Map.of(
            "agent",
            "Queue",
            "label",
            "投递未完成，持久任务将重试",
            "status",
            "warning"
          )
        );
      }
    } else if (worker) try {
      executor.execute(() -> consume(id));
    } catch (RejectedExecutionException ignored) {
      /* QUEUED is a durable outbox; recovery retries it. */
    }
  }

  @Scheduled(fixedDelay = 5000, initialDelay = 5000)
  public void recover() {
    if (!ready) return;
    journal
      .database()
      .jdbc.update(
        "UPDATE agent_run SET status='QUEUED',lease_token=NULL,lease_until=NULL WHERE status='RUNNING' AND lease_until<? AND attempts<3",
        Timestamp.from(Instant.now())
      );
    journal
      .database()
      .jdbc.update(
        "UPDATE agent_run SET status='FAILED',lease_token=NULL,lease_until=NULL WHERE (status='RUNNING' AND lease_until<? OR status='QUEUED') AND attempts>=3",
        Timestamp.from(Instant.now())
      );
    for (String id : journal
      .database()
      .jdbc.queryForList(
        "SELECT id FROM agent_run WHERE status='QUEUED' ORDER BY created_at LIMIT 16",
        String.class
      ))
      submit(id);
  }

  @Scheduled(fixedDelay = 10000, initialDelay = 10000)
  public void heartbeat() {
    active.forEach((id, lease) -> {
      if (!journal.heartbeat(id, lease)) active.remove(id, lease);
    });
  }

  public void consume(String id) {
    if (!worker || !ready) return;
    String lease = journal.claim(id);
    if (lease == null) return;
    active.put(id, lease);
    try (var scope = ExecutionScope.enter(id, lease)) {
      journal.finish(id, graph.execute(id), lease);
    } catch (ExecutionScope.StaleExecutionException ignored) {
      /* Never let a cancelled/expired execution overwrite a newer owner. */
    } catch (Exception e) {
      journal.fail(id, lease);
    } finally {
      active.remove(id, lease);
      traces.export(id);
    }
  }

  @Configuration
  @ConditionalOnProperty(name = "smartserve.async-mode", havingValue = "rabbit")
  static class RabbitWorker {

    private final RunDispatcher dispatcher;

    RabbitWorker(RunDispatcher dispatcher) {
      this.dispatcher = dispatcher;
    }

    @Bean
    org.springframework.amqp.core.Queue runs() {
      return QueueBuilder.durable(dispatcher.queue)
        .deadLetterExchange("")
        .deadLetterRoutingKey(dispatcher.queue + ".dlq")
        .build();
    }

    @Bean
    org.springframework.amqp.core.Queue deadLetters() {
      return QueueBuilder.durable(dispatcher.queue + ".dlq").build();
    }

    @RabbitListener(
      queues = "${smartserve.run-queue}",
      concurrency = "2-4",
      autoStartup = "${WORKER_ENABLED:true}"
    )
    void receive(String id) {
      dispatcher.consume(id);
    }
  }
}
