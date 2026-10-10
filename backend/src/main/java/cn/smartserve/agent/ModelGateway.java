package cn.smartserve.agent;

import cn.smartserve.infra.JsonStore;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ModelGateway {

  @Value("${smartserve.llm-enabled}")
  public boolean enabled;

  @Value("${smartserve.deepseek-key}")
  String key;

  @Value("${smartserve.deepseek-url}")
  String url;

  @Value("${smartserve.deepseek-model}")
  String modelName;

  private ChatLanguageModel model;
  private final Semaphore permits = new Semaphore(4);

  @PostConstruct
  void init() {
    if (enabled) {
      if (key.isBlank()) throw new IllegalStateException(
        "LLM_ENABLED=true 时必须设置 DEEPSEEK_API_KEY"
      );
      model = OpenAiChatModel.builder()
        .apiKey(key)
        .baseUrl(url)
        .modelName(modelName)
        .temperature(0.0)
        .timeout(Duration.ofSeconds(45))
        .maxRetries(1)
        .logRequests(false)
        .logResponses(false)
        .build();
    }
  }

  public record Completion(String text, int inputTokens, int outputTokens) {}

  public Completion complete(String system, String user) {
    if (!enabled) throw new IllegalStateException("模型调用未启用");
    if (!permits.tryAcquire()) throw new IllegalStateException(
      "模型并发已满，请稍后重试"
    );
    try {
      var response = model.generate(
        List.of(SystemMessage.from(system), UserMessage.from(user))
      );
      var usage = response.tokenUsage();
      return new Completion(
        response.content().text(),
        usage == null ? 0 : usage.inputTokenCount(),
        usage == null ? 0 : usage.outputTokenCount()
      );
    } finally {
      permits.release();
    }
  }
}
