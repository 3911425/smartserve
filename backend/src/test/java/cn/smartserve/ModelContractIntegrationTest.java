package cn.smartserve;

import static org.assertj.core.api.Assertions.*;

import cn.smartserve.agent.*;
import cn.smartserve.business.BusinessService;
import cn.smartserve.infra.JsonStore;
import cn.smartserve.security.Access.Actor;
import cn.smartserve.security.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Real LC4j HTTP serialization + MCP + graph. This fixture is not a quality test of DeepSeek. */
@SpringBootTest(
  webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
  properties = {
    "server.port=18180",
    "spring.datasource.url=jdbc:h2:mem:model_contract;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "smartserve.data-path=../data",
    "smartserve.llm-enabled=true",
    "smartserve.model-api-key=contract-test-only",
    "smartserve.tracing-enabled=false",
    "smartserve.async-mode=local",
    "smartserve.bootstrap-admin-password=TestAdmin-Only-2026",
    "WORKER_ENABLED=false",
  }
)
class ModelContractIntegrationTest {

  static final ObjectMapper JSON = new ObjectMapper();
  static final AtomicBoolean RETRY = new AtomicBoolean();
  static final AtomicInteger REQUESTS = new AtomicInteger();
  static final List<Map<String, Object>> BODIES = Collections.synchronizedList(
    new ArrayList<>()
  );
  static HttpServer server;

  @DynamicPropertySource
  static void endpoint(DynamicPropertyRegistry registry) throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/chat/completions", exchange -> {
      try {
        REQUESTS.incrementAndGet();
        var input = JSON.readValue(exchange.getRequestBody(), Map.class);
        BODIES.add(input);
        byte[] body;
        int status = 200;
        if (RETRY.getAndSet(false)) {
          status = 503;
          body =
            "{\"error\":{\"message\":\"temporary unavailable\",\"type\":\"server_error\"}}".getBytes(
              StandardCharsets.UTF_8
            );
        } else {
          var messages = (List<Map<String, Object>>) input.get("messages");
          String system = messages.get(0).get("content").toString();
          Map<String, Object> message = new LinkedHashMap<>();
          message.put("role", "assistant");
          String finish = "stop";
          if (system.contains("意图路由器")) message.put(
            "content",
            "{\"routes\":[\"PRODUCT\"]}"
          );
          else if (system.contains("证据审查员")) message.put(
            "content",
            "{\"supported\":true}"
          );
          else if (
            messages.stream().noneMatch(m -> "tool".equals(m.get("role")))
          ) {
            message.put(
              "tool_calls",
              List.of(
                Map.of(
                  "id",
                  "contract-call-1",
                  "type",
                  "function",
                  "function",
                  Map.of(
                    "name",
                    "searchKnowledge",
                    "arguments",
                    "{\"query\":\"S1门锁没电Type-C应急供电\"}"
                  )
                )
              )
            );
            finish = "tool_calls";
          } else message.put(
            "content",
            "{\"answer\":\"S1 门锁可使用 Type-C 应急供电开门。 [S1]\",\"needsUserInput\":false,\"needsEscalation\":false}"
          );
          body = JSON.writeValueAsBytes(
            Map.of(
              "id",
              "contract-response",
              "object",
              "chat.completion",
              "created",
              1,
              "model",
              "deepseek-v4.1-flash",
              "choices",
              List.of(
                Map.of("index", 0, "message", message, "finish_reason", finish)
              ),
              "usage",
              Map.of(
                "prompt_tokens",
                100,
                "completion_tokens",
                20,
                "total_tokens",
                120
              )
            )
          );
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    server.start();
    registry.add(
      "smartserve.model-base-url",
      () -> "http://127.0.0.1:" + server.getAddress().getPort()
    );
  }

  @Autowired
  AuthService auth;

  @Autowired
  BusinessService business;

  @Autowired
  RunJournal journal;

  @Autowired
  AgentOrchestrator graph;

  @Autowired
  JsonStore db;

  @AfterAll
  static void stop() {
    server.stop(0);
  }

  Map<String, Object> execute(boolean retry) {
    RETRY.set(retry);
    REQUESTS.set(0);
    BODIES.clear();
    var u = auth.register(
      "contract-" + UUID.randomUUID().toString().substring(0, 8),
      "Contract-2026",
      "协议测试"
    );
    var actor = new Actor(u.get("id").toString(), false);
    var c = business.newConversation(actor);
    String id = journal
      .create(
        actor,
        c.get("id").toString(),
        "S1门锁没电了怎么开门？",
        UUID.randomUUID().toString()
      )
      .get("id")
      .toString();
    String lease = journal.claim(id);
    try (var scope = ExecutionScope.enter(id, lease)) {
      journal.finish(id, graph.execute(id), lease);
    }
    return journal.get(actor, id);
  }

  @Test
  void nativeToolLoopUsesFourModelCallsAndRealMcpEvidence() {
    var result = execute(false);
    assertThat(result.get("answer").toString()).contains("Type-C", "[S1]");
    assertThat(REQUESTS).hasValue(4);
    assertThat(((Number) result.get("modelCalls")).intValue()).isEqualTo(4);
    assertThat(((Number) result.get("tokens")).intValue()).isEqualTo(480);
    assertThat(BODIES.stream().anyMatch(b -> b.containsKey("tools"))).isTrue();
    assertThat(
      BODIES.stream().anyMatch(b ->
        ((List<Map<String, Object>>) b.get("messages")).stream().anyMatch(
          m ->
            "tool".equals(m.get("role")) &&
            "contract-call-1".equals(m.get("tool_call_id"))
        )
      )
    ).isTrue();
    assertThat(
      journal
        .events(result.get("id").toString(), 0)
        .stream()
        .anyMatch(
          e ->
            "tool".equals(e.get("type")) &&
            "searchKnowledge".equals(e.get("tool"))
        )
    ).isTrue();
  }

  @Test
  void transientRetryIsAnAdditionalVisibleAttemptWithUnknownFailedUsage() {
    var result = execute(true);
    assertThat(REQUESTS).hasValue(5);
    assertThat(((Number) result.get("modelCalls")).intValue()).isEqualTo(5);
    assertThat(((Number) result.get("failedModelCalls")).intValue()).isEqualTo(
      1
    );
    assertThat(
      db.jdbc.queryForObject(
        "SELECT COUNT(*) FROM model_call WHERE run_id=? AND status='ERROR' AND input_tokens IS NULL AND output_tokens IS NULL",
        Integer.class,
        result.get("id")
      )
    ).isEqualTo(1);
  }
}
