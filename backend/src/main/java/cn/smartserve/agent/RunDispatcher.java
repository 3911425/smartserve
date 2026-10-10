package cn.smartserve.agent;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Service;

@Service
@EnableScheduling
public class RunDispatcher implements ApplicationRunner {

  private final RunJournal journal;
  private final AgentOrchestrator graph;
  private final RabbitTemplate rabbit;
  private final TraceExporter traces;

  @Value("${smartserve.async-mode}")
  String mode;

  @Value("${WORKER_ENABLED:true}")
  boolean worker;

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

  @Override
  public void run(ApplicationArguments a) {}

  public void submit(String id) {
    if (mode.equals("rabbit")) {
      try {
        rabbit.convertAndSend("smartserve.runs", id);
      } catch (Exception e) {
        journal.event(
          id,
          "queue_retry",
          Map.of(
            "agent",
            "Queue",
            "label",
            "消息投递暂未完成，持久任务将重试",
            "status",
            "warning"
          )
        );
      }
    } else if (worker) {
      try {
        executor.execute(() -> consume(id));
      } catch (RejectedExecutionException e) {
        /* QUEUED remains a durable outbox; scheduled dispatch retries. */
      }
    }
  }

  @Scheduled(fixedDelay = 5000, initialDelay = 5000)
  public void recover() {
    // Expired execution leases are retried from the persisted graph checkpoint.
    journal
      .database()
      .jdbc.update(
        "UPDATE agent_run SET status='QUEUED' WHERE status='RUNNING' AND updated_at<?",
        java.sql.Timestamp.from(Instant.now().minusSeconds(180))
      );
    for (String id : journal
      .database()
      .jdbc.queryForList(
        "SELECT id FROM agent_run WHERE status='QUEUED' ORDER BY created_at LIMIT 16",
        String.class
      ))
      submit(id);
  }

  public void consume(String id) {
    if (!worker) return;
    if (
      journal
        .database()
        .jdbc.update(
          "UPDATE agent_run SET status='RUNNING',updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='QUEUED'",
          id
        ) !=
      1
    ) return;
    try {
      var result = graph.execute(id);
      journal.finish(id, result);
    } catch (Exception e) {
      journal.fail(id);
    } finally {
      traces.export(id);
    }
  }

  @Configuration
  @ConditionalOnProperty(name = "smartserve.async-mode", havingValue = "rabbit")
  static class RabbitWorker {

    @Bean
    org.springframework.amqp.core.Queue runs() {
      return QueueBuilder.durable("smartserve.runs")
        .deadLetterExchange("")
        .deadLetterRoutingKey("smartserve.runs.dlq")
        .build();
    }

    @Bean
    org.springframework.amqp.core.Queue deadLetters() {
      return QueueBuilder.durable("smartserve.runs.dlq").build();
    }

    @RabbitListener(
      queues = "smartserve.runs",
      concurrency = "2-4",
      autoStartup = "${WORKER_ENABLED:true}"
    )
    void receive(String id, org.springframework.amqp.core.Message message) {
      dispatcher.consume(id);
    }

    private final RunDispatcher dispatcher;

    RabbitWorker(RunDispatcher dispatcher) {
      this.dispatcher = dispatcher;
    }
  }
}
