package cn.smartserve.agent;

import cn.smartserve.infra.JsonStore;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.Response;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Exactly one telemetry row per SDK attempt; prompt contents and credentials never enter telemetry. */
@Service
public class ModelGateway {

  @Value("${smartserve.llm-enabled}")
  public boolean enabled;

  @Value("${smartserve.model-api-key}")
  String key;

  @Value("${smartserve.model-base-url}")
  String url;

  @Value("${smartserve.model-name}")
  String modelName;

  @Value("${smartserve.model-provider}")
  public String provider;

  @Value("${smartserve.model-max-tokens}")
  int maxTokens;

  @Value("${smartserve.model-timeout-seconds}")
  int timeout;

  @Value("${smartserve.model-retries}")
  int retries;

  @Value("${smartserve.agent-max-calls}")
  int maxCalls;

  private ChatLanguageModel model;
  private final Semaphore permits = new Semaphore(4);
  private final JsonStore db;
  private final RunJournal journal;

  public ModelGateway(JsonStore db, RunJournal journal) {
    this.db = db;
    this.journal = journal;
  }

  @PostConstruct
  void init() {
    if (enabled) {
      if (key.isBlank()) throw new IllegalStateException(
        "LLM_ENABLED=true 时需要配置 LLM_API_KEY"
      );
      // In LangChain4j 0.36.2 this setting is the total SDK attempt count. Retries below are explicit and traceable.
      model = OpenAiChatModel.builder()
        .apiKey(key)
        .baseUrl(url)
        .modelName(modelName)
        .temperature(0.0)
        .timeout(Duration.ofSeconds(timeout))
        .maxRetries(1)
        .maxTokens(maxTokens)
        .logRequests(false)
        .logResponses(false)
        .build();
    }
  }

  public record Completion(String text, int inputTokens, int outputTokens) {}

  public Completion complete(
    String run,
    String node,
    String purpose,
    String system,
    String user
  ) {
    var r = step(
      run,
      node,
      purpose,
      List.of(SystemMessage.from(system), UserMessage.from(user)),
      List.of()
    );
    var u = r.tokenUsage();
    return new Completion(
      Objects.toString(r.content().text(), ""),
      u == null ? 0 : u.inputTokenCount(),
      u == null ? 0 : u.outputTokenCount()
    );
  }

  public Response<AiMessage> step(
    String run,
    String node,
    String purpose,
    List<ChatMessage> messages,
    List<ToolSpecification> tools
  ) {
    if (!enabled) throw new IllegalStateException("模型已关闭");
    RuntimeException last = null;
    for (
      int attempt = 1;
      attempt <= Math.min(2, Math.max(0, retries) + 1);
      attempt++
    ) {
      ExecutionScope.check(db);
      if (
        db.jdbc.queryForObject(
          "SELECT COUNT(*) FROM model_call WHERE run_id=?",
          Integer.class,
          run
        ) >=
        maxCalls
      ) throw new IllegalStateException("本次任务的模型调用预算已用尽");
      if (!permits.tryAcquire()) throw new IllegalStateException(
        "模型并发繁忙，请稍后重试"
      );
      long start = System.nanoTime();
      try {
        var response = tools.isEmpty()
          ? model.generate(messages)
          : model.generate(messages, tools);
        var usage = response.tokenUsage();
        record(
          run,
          node,
          purpose,
          attempt,
          "SUCCESS",
          usage == null ? null : usage.inputTokenCount(),
          usage == null ? null : usage.outputTokenCount(),
          start,
          ""
        );
        ExecutionScope.check(db);
        return response;
      } catch (ExecutionScope.StaleExecutionException stale) {
        throw stale;
      } catch (RuntimeException e) {
        record(
          run,
          node,
          purpose,
          attempt,
          "ERROR",
          null,
          null,
          start,
          e.getClass().getSimpleName()
        );
        last = e;
        String error = Objects.toString(e.getMessage(), "").toLowerCase(
          Locale.ROOT
        );
        boolean transientHttp = false;
        for (Throwable cause = e; cause != null; cause = cause.getCause()) if (
          cause instanceof dev.ai4j.openai4j.OpenAiHttpException http
        ) transientHttp = Set.of(429, 502, 503, 504).contains(http.code());
        if (
          !(transientHttp ||
            error.contains("429") ||
            error.contains("502") ||
            error.contains("503") ||
            error.contains("timeout") ||
            error.contains("timed out") ||
            error.contains("connect"))
        ) throw e;
      } finally {
        permits.release();
      }
    }
    throw last == null ? new IllegalStateException("模型未返回结果") : last;
  }

  private void record(
    String run,
    String node,
    String purpose,
    int attempt,
    String status,
    Integer input,
    Integer output,
    long start,
    String error
  ) {
    long duration = (System.nanoTime() - start) / 1_000_000;
    String id = JsonStore.id("model-");
    db.jdbc.update(
      "INSERT INTO model_call(id,run_id,node,purpose,model,attempt,status,input_tokens,output_tokens,duration_ms,error_code) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
      id,
      run,
      node,
      purpose,
      modelName,
      attempt,
      status,
      input,
      output,
      duration,
      error
    );
    var event = new LinkedHashMap<String, Object>();
    event.put("agent", node);
    event.put(
      "label",
      purpose + " · " + (status.equals("SUCCESS") ? "模型返回" : "模型请求失败")
    );
    event.put("status", status.equals("SUCCESS") ? "ok" : "warning");
    event.put("durationMs", duration);
    event.put("callId", id);
    event.put("attempt", attempt);
    event.put("model", modelName);
    event.put("inputTokens", input);
    event.put("outputTokens", output);
    event.put("purpose", purpose);
    journal.event(run, "model", event);
  }
}
