package cn.smartserve.api;

import cn.smartserve.agent.*;
import cn.smartserve.business.BusinessService;
import cn.smartserve.rag.KnowledgeService;
import cn.smartserve.security.Access;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class ApiController {

  private final BusinessService business;
  private final Access access;
  private final KnowledgeService knowledge;
  private final RunJournal runs;
  private final RunDispatcher dispatcher;
  private final ModelGateway model;
  private final ScheduledExecutorService streams =
    Executors.newScheduledThreadPool(2, r -> {
      Thread t = new Thread(r, "sse-events");
      t.setDaemon(true);
      return t;
    });
  private final Semaphore streamLimit = new Semaphore(64);

  public ApiController(
    BusinessService business,
    Access access,
    KnowledgeService knowledge,
    RunJournal runs,
    RunDispatcher dispatcher,
    ModelGateway model
  ) {
    this.business = business;
    this.access = access;
    this.knowledge = knowledge;
    this.runs = runs;
    this.dispatcher = dispatcher;
    this.model = model;
  }

  public record SessionInput(
    @NotNull @Pattern(regexp = "customer|admin") String role
  ) {}

  @PostMapping("/demo/session")
  Object session(@Valid @RequestBody SessionInput in) {
    return access.issue(in.role());
  }

  @GetMapping("/status")
  Object status() {
    return Map.of(
      "name",
      "SmartServe",
      "version",
      "1.0.0",
      "demoAccess",
      access.demo,
      "modelEnabled",
      model.enabled,
      "modelMode",
      model.enabled ? "DeepSeek" : "离线证据演示",
      "knowledge",
      knowledge.stats(),
      "paymentMode",
      "SIMULATED"
    );
  }

  @GetMapping("/products")
  Object products() {
    return business.products();
  }

  @GetMapping("/orders")
  Object orders(HttpServletRequest r) {
    return business.orders(Access.actor(r));
  }

  @GetMapping("/orders/{id}")
  Object order(@PathVariable String id, HttpServletRequest r) {
    return business.order(Access.actor(r), id);
  }

  @PostMapping("/conversations")
  Object newConversation(HttpServletRequest r) {
    return business.newConversation(Access.actor(r));
  }

  @GetMapping("/conversations")
  Object conversations(HttpServletRequest r) {
    return business.database().list("conversation", Access.actor(r).userId());
  }

  @GetMapping("/conversations/{id}/messages")
  Object messages(@PathVariable String id, HttpServletRequest r) {
    return business.messages(Access.actor(r), id);
  }

  public record RunInput(
    @NotBlank @Size(max = 100) String conversationId,
    @NotBlank @Size(max = 2000) String question,
    @NotBlank @Size(max = 100) String requestKey
  ) {}

  @PostMapping("/runs")
  Object createRun(@Valid @RequestBody RunInput in, HttpServletRequest r) {
    var run = runs.create(
      Access.actor(r),
      in.conversationId(),
      in.question(),
      in.requestKey()
    );
    if ("QUEUED".equals(run.get("status"))) dispatcher.submit(
      (String) run.get("id")
    );
    return run;
  }

  @GetMapping("/runs")
  Object runs(HttpServletRequest r) {
    return runs.list(Access.actor(r));
  }

  @GetMapping("/runs/{id}")
  Object run(@PathVariable String id, HttpServletRequest r) {
    return runs.get(Access.actor(r), id);
  }

  @GetMapping("/runs/{id}/events")
  Object events(
    @PathVariable String id,
    @RequestParam(defaultValue = "0") long after,
    HttpServletRequest r
  ) {
    runs.get(Access.actor(r), id);
    return runs.events(id, after);
  }

  @GetMapping(
    value = "/runs/{id}/stream",
    produces = MediaType.TEXT_EVENT_STREAM_VALUE
  )
  SseEmitter stream(
    @PathVariable String id,
    @RequestHeader(value = "Last-Event-ID", defaultValue = "0") long last,
    HttpServletRequest r
  ) {
    runs.get(Access.actor(r), id);
    if (!streamLimit.tryAcquire()) throw new ResponseStatusException(
      HttpStatus.TOO_MANY_REQUESTS,
      "连接过多，请稍后重试"
    );
    SseEmitter emitter = new SseEmitter(240000L);
    AtomicBoolean done = new AtomicBoolean(false);
    long[] cursor = { last };
    Runnable cleanup = () -> {
      if (done.compareAndSet(false, true)) streamLimit.release();
    };
    emitter.onCompletion(cleanup);
    emitter.onTimeout(cleanup);
    emitter.onError(e -> cleanup.run());
    ScheduledFuture<?>[] task = new ScheduledFuture<?>[1];
    task[0] = streams.scheduleWithFixedDelay(
      () -> {
        if (done.get()) {
          if (task[0] != null) task[0].cancel(false);
          return;
        }
        try {
          for (var event : runs.events(id, cursor[0])) {
            cursor[0] = ((Number) event.get("eventId")).longValue();
            emitter.send(
              SseEmitter.event()
                .id(Long.toString(cursor[0]))
                .name(event.get("type").toString())
                .data(event)
            );
          }
          String state = runs.raw(id).get("status").toString();
          if (Set.of("COMPLETED", "FAILED").contains(state)) {
            emitter.complete();
            cleanup.run();
          }
        } catch (Exception e) {
          emitter.completeWithError(e);
          cleanup.run();
        }
      },
      0,
      250,
      TimeUnit.MILLISECONDS
    );
    return emitter;
  }

  public record RefundInput(
    @NotBlank @Size(max = 100) String orderId,
    @NotBlank @Size(max = 500) String reason
  ) {}

  public record Confirmation(@AssertTrue boolean confirmed) {}

  @PostMapping("/refunds")
  Object refund(@Valid @RequestBody RefundInput in, HttpServletRequest r) {
    return business.createRefund(Access.actor(r), in.orderId(), in.reason());
  }

  @GetMapping("/refunds/{id}")
  Object refund(@PathVariable String id, HttpServletRequest r) {
    return business.refund(Access.actor(r), id);
  }

  @PostMapping("/refunds/{id}/confirm")
  Object confirmRefund(
    @PathVariable String id,
    @Valid @RequestBody Confirmation in,
    HttpServletRequest r
  ) {
    return business.confirmRefund(Access.actor(r), id, in.confirmed());
  }

  public record TicketInput(
    @NotBlank @Size(max = 100) String conversationId,
    @NotNull @Size(max = 100) String orderId,
    @NotBlank @Size(max = 100) String title,
    @NotBlank @Size(max = 2000) String details,
    @NotBlank @Size(max = 100) String contact,
    @NotNull @Pattern(regexp = "NORMAL|HIGH") String priority,
    @NotBlank @Size(max = 100) String requestKey,
    @AssertTrue boolean confirmed
  ) {}

  @PostMapping("/tickets")
  Object ticket(@Valid @RequestBody TicketInput in, HttpServletRequest r) {
    return business.createTicket(
      Access.actor(r),
      in.conversationId(),
      in.orderId(),
      in.title(),
      in.details(),
      in.contact(),
      in.priority(),
      in.requestKey(),
      in.confirmed()
    );
  }

  @GetMapping("/tickets")
  Object tickets(HttpServletRequest r) {
    return business.tickets(Access.actor(r));
  }

  public record TicketUpdate(
    @NotNull @Pattern(regexp = "PROCESSING|RESOLVED") String status,
    @NotNull @Size(max = 2000) String resolution,
    @NotNull @Size(max = 2000) String note,
    @Min(0) int version
  ) {}

  @PutMapping("/tickets/{id}")
  Object update(
    @PathVariable String id,
    @Valid @RequestBody TicketUpdate in,
    HttpServletRequest r
  ) {
    return business.updateTicket(
      Access.actor(r),
      id,
      in.status(),
      in.resolution(),
      in.note(),
      in.version()
    );
  }

  @GetMapping("/knowledge")
  Object documents() {
    return knowledge.documents();
  }

  @GetMapping("/knowledge/search")
  Object search(
    @RequestParam @Size(max = 2000) String query,
    @RequestParam(defaultValue = "hybrid") String strategy
  ) {
    if (
      query.length() > 2000 ||
      !Set.of("hybrid", "vector", "bm25").contains(strategy)
    ) throw new ResponseStatusException(
      HttpStatus.BAD_REQUEST,
      "检索参数不合法"
    );
    return knowledge.search(query, 5, strategy);
  }

  @PostMapping("/knowledge/reindex")
  Object reindex(HttpServletRequest r) throws Exception {
    Access.admin(Access.actor(r));
    return knowledge.reindex();
  }
}
