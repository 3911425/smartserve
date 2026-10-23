package cn.smartserve.agent;

import cn.smartserve.infra.JsonStore;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class TraceExporter {

  @Value("${smartserve.tracing-enabled}")
  boolean enabled;

  @Value("${smartserve.langsmith-key}")
  String key;

  @Value("${smartserve.langsmith-url}")
  String endpoint;

  @Value("${smartserve.langsmith-project}")
  String project;

  @Value("${smartserve.model-provider}")
  String modelProvider;

  private final JsonStore db;
  private final RunJournal journal;
  private final ExecutorService executor = new ThreadPoolExecutor(
    1,
    1,
    0,
    TimeUnit.MILLISECONDS,
    new ArrayBlockingQueue<>(64),
    r -> {
      Thread t = new Thread(r, "langsmith-export");
      t.setDaemon(true);
      return t;
    },
    new ThreadPoolExecutor.DiscardPolicy()
  );
  private final HttpClient client = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(5))
    .build();

  public TraceExporter(JsonStore db, RunJournal journal) {
    this.db = db;
    this.journal = journal;
  }

  public void export(String runId) {
    if (!enabled || key.isBlank()) return;
    executor.execute(() -> {
      try {
        var run = journal.raw(runId);
        String trace = (String) run.get("traceId");
        String start = run.get("createdAt").toString();
        String dotted = dottedOrder(Instant.parse(start), trace);
        var body = new LinkedHashMap<String, Object>();
        body.put("id", trace);
        body.put("trace_id", trace);
        body.put("dotted_order", dotted);
        body.put("name", "SmartServe.V2.CustomerService");
        body.put("run_type", "chain");
        body.put("session_name", project);
        body.put("start_time", start);
        body.put("end_time", Instant.now().toString());
        body.put(
          "inputs",
          Map.of(
            "question_length",
            run.get("question").toString().length(),
            "user_hash",
            cn.smartserve.business.BusinessService.hash(
              run.get("userId").toString()
            ).substring(0, 12)
          )
        );
        body.put(
          "outputs",
          Map.of(
            "status",
            run.get("status"),
            "routes",
            run.getOrDefault("routes", List.of()),
            "tokens",
            run.getOrDefault("tokens", 0)
          )
        );
        post(body);
        for (var event : journal.events(runId, 0)) {
          String child = UUID.randomUUID().toString();
          var span = new LinkedHashMap<String, Object>();
          span.put("id", child);
          span.put("trace_id", trace);
          span.put("parent_run_id", trace);
          span.put(
            "dotted_order",
            dotted +
              "." +
              dottedOrder(Instant.parse(event.get("at").toString()), child)
          );
          span.put(
            "name",
            event.getOrDefault("agent", "SmartServe") + "." + event.get("type")
          );
          span.put(
            "run_type",
            "model".equals(event.get("type"))
              ? "llm"
              : "tool".equals(event.get("type"))
                ? "tool"
                : "chain"
          );
          span.put(
            "start_time",
            Instant.parse(event.get("at").toString())
              .minusMillis(
                ((Number) event.getOrDefault("durationMs", 0)).longValue()
              )
              .toString()
          );
          span.put("end_time", event.get("at"));
          span.put("inputs", Map.of("event_type", event.get("type")));
          span.put(
            "outputs",
            Map.of(
              "duration_ms",
              event.getOrDefault("durationMs", 0),
              "status",
              event.getOrDefault("status", "ok")
            )
          );
          if ("model".equals(event.get("type"))) {
            span.put(
              "extra",
              Map.of(
                "metadata",
                Map.of(
                  "ls_provider",
                  modelProvider,
                  "ls_model_name",
                  event.getOrDefault("model", "unknown"),
                  "purpose",
                  event.getOrDefault("purpose", ""),
                  "attempt",
                  event.getOrDefault("attempt", 1)
                )
              )
            );
            Object input = event.get("inputTokens"),
              output = event.get("outputTokens");
            if (input instanceof Number a && output instanceof Number b) {
              span.put("prompt_tokens", a.intValue());
              span.put("completion_tokens", b.intValue());
              span.put("total_tokens", a.intValue() + b.intValue());
              span.put(
                "outputs",
                Map.of(
                  "usage_metadata",
                  Map.of(
                    "input_tokens",
                    a.intValue(),
                    "output_tokens",
                    b.intValue(),
                    "total_tokens",
                    a.intValue() + b.intValue()
                  )
                )
              );
            }
            if ("warning".equals(event.get("status"))) span.put(
              "error",
              "Model attempt failed; see local model_call record"
            );
          }
          post(span);
        }
        journal.event(
          runId,
          "trace_export",
          Map.of(
            "agent",
            "LangSmith",
            "label",
            "脱敏追踪已导出",
            "status",
            "ok"
          )
        );
      } catch (Exception e) {
        journal.event(
          runId,
          "trace_export",
          Map.of(
            "agent",
            "LangSmith",
            "label",
            "远程追踪导出失败，本地记录完整保留",
            "status",
            "warning"
          )
        );
      }
    });
  }

  @jakarta.annotation.PreDestroy
  public void stop() {
    executor.shutdownNow();
  }

  private static String dottedOrder(Instant time, String id) {
    return (
      java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSSSSS'Z'")
        .withZone(java.time.ZoneOffset.UTC)
        .format(time) +
      id
    );
  }

  private void post(Map<String, Object> body) throws Exception {
    var req = HttpRequest.newBuilder(URI.create(endpoint + "/runs"))
      .timeout(Duration.ofSeconds(10))
      .header("x-api-key", key)
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(db.json(body)))
      .build();
    var res = client.send(req, HttpResponse.BodyHandlers.discarding());
    if (res.statusCode() / 100 != 2) throw new IllegalStateException(
      "Trace export rejected"
    );
  }
}
