package cn.smartserve;

import static org.assertj.core.api.Assertions.*;

import cn.smartserve.agent.*;
import cn.smartserve.business.*;
import cn.smartserve.infra.JsonStore;
import cn.smartserve.rag.KnowledgeService;
import cn.smartserve.security.Access;
import cn.smartserve.security.Access.Actor;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(
  webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
  properties = {
    "server.port=18080",
    "spring.datasource.url=jdbc:h2:mem:smartserve_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "smartserve.data-path=../data",
    "smartserve.llm-enabled=false",
    "smartserve.tracing-enabled=false",
    "smartserve.async-mode=local",
    "WORKER_ENABLED=false",
  }
)
class SmartServeIntegrationTest {

  @Autowired
  BusinessService business;

  @Autowired
  JsonStore db;

  @Autowired
  KnowledgeService knowledge;

  @Autowired
  ToolRegistry tools;

  @Autowired
  RunJournal journal;

  @Autowired
  AgentOrchestrator graph;

  @Autowired
  JdbcCheckpointSaver saver;

  @Autowired
  Access access;

  @Autowired
  TestRestTemplate http;

  private Actor actor;
  private String orderId;

  @BeforeEach
  void fixture() {
    actor = new Actor("test-" + UUID.randomUUID(), false);
    orderId = "SS" + Math.abs(System.nanoTime());
    var o = new LinkedHashMap<String, Object>();
    o.put("id", orderId);
    o.put("userId", actor.userId());
    o.put("productName", "测试门锁");
    o.put("productId", "lock-s1");
    o.put("amount", new BigDecimal("1299.00"));
    o.put("status", "DELIVERED");
    o.put(
      "deliveredAt",
      LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(2).toString()
    );
    o.put("logistics", "样例已签收");
    o.put("trackingNumber", "TEST-SF");
    db.insert("customer_order", orderId, actor.userId(), o);
  }

  @Test
  void preventsCrossCustomerOrderAccess() {
    assertThatThrownBy(() -> business.order(new Actor("other", false), orderId))
      .isInstanceOf(ResponseStatusException.class)
      .hasMessageContaining("403");
    assertThat(
      business.order(new Actor("admin", true), orderId).get("id")
    ).isEqualTo(orderId);
  }

  @Test
  void administratorRefundDraftStillBelongsToTheOrderCustomer() {
    var draft = business.createRefund(
      new Actor("operator", true),
      orderId,
      "代客户登记"
    );
    assertThat(
      business.refund(actor, draft.get("id").toString()).get("orderId")
    ).isEqualTo(orderId);
  }

  @Test
  void unrelatedMedicalQuestionIsNotAcceptedAsCustomerServiceEvidence() {
    assertThat(
      knowledge
        .search("怎样治疗流感并开处方药？", 4)
        .stream()
        .noneMatch(c -> Boolean.TRUE.equals(c.get("accepted")))
    ).isTrue();
  }

  @Test
  void mcpRejectsUntrustedOriginEvenWithAServiceCredential() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth("local-demo-mcp-token");
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Origin", "https://attacker.invalid");
    assertThat(
      http
        .postForEntity(
          "/api/mcp",
          new HttpEntity<>(
            Map.of("jsonrpc", "2.0", "id", 1, "method", "ping"),
            headers
          ),
          String.class
        )
        .getStatusCode()
    ).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void concurrentRefundDraftsHaveOneConsequence() throws Exception {
    var executor = Executors.newFixedThreadPool(8);
    try {
      var futures = new ArrayList<Future<Map<String, Object>>>();
      for (int i = 0; i < 16; i++) futures.add(
        executor.submit(() ->
          business.createRefund(actor, orderId, "无理由退货")
        )
      );
      Set<Object> ids = new HashSet<>();
      for (var future : futures)
        ids.add(future.get(30, TimeUnit.SECONDS).get("id"));
      assertThat(ids).hasSize(1);
      assertThat(
        db.jdbc.queryForObject(
          "SELECT COUNT(*) FROM refund_request WHERE order_id=?",
          Integer.class,
          orderId
        )
      ).isEqualTo(1);
      assertThat(business.order(actor, orderId).get("status")).isEqualTo(
        "DELIVERED"
      );
      String refund = ids.iterator().next().toString();
      assertThatThrownBy(() ->
        business.confirmRefund(actor, refund, false)
      ).isInstanceOf(ResponseStatusException.class);
      business.confirmRefund(actor, refund, true);
      business.confirmRefund(actor, refund, true);
      assertThat(business.order(actor, orderId).get("status")).isEqualTo(
        "REFUND_PENDING"
      );
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void ineligibleOrderCannotCreateAutomaticRefund() {
    var o = business.order(actor, orderId);
    o.put("deliveredAt", LocalDate.now().minusDays(12).toString());
    db.update("customer_order", orderId, o, 0);
    assertThatThrownBy(() ->
      business.createRefund(actor, orderId, "无理由退款")
    )
      .isInstanceOf(ResponseStatusException.class)
      .hasMessageContaining("409");
  }

  @Test
  void ticketNeedsConfirmationAndReturnsResolutionToOriginalConversation() {
    String conv = business.newConversation(actor).get("id").toString();
    assertThatThrownBy(() ->
      business.createTicket(
        actor,
        conv,
        orderId,
        "设备异常",
        "需要核验安装问题",
        "test@example.invalid",
        "NORMAL",
        "req-1",
        false
      )
    ).isInstanceOf(ResponseStatusException.class);
    var t = business.createTicket(
      actor,
      conv,
      orderId,
      "设备异常",
      "需要核验安装问题",
      "test@example.invalid",
      "NORMAL",
      "req-1",
      true
    );
    assertThat(
      business
        .createTicket(
          actor,
          conv,
          orderId,
          "设备异常",
          "需要核验安装问题",
          "test@example.invalid",
          "NORMAL",
          "req-1",
          true
        )
        .get("id")
    ).isEqualTo(t.get("id"));
    String id = t.get("id").toString();
    Actor admin = new Actor("admin", true);
    assertThatThrownBy(() ->
      business.updateTicket(actor, id, "PROCESSING", "", "", 0)
    ).isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() ->
      business.updateTicket(admin, id, "RESOLVED", "已核验", "", 0)
    ).isInstanceOf(ResponseStatusException.class);
    business.updateTicket(admin, id, "PROCESSING", "", "仅内部可见", 0);
    assertThatThrownBy(() ->
      business.updateTicket(admin, id, "PROCESSING", "", "旧版本", 0)
    )
      .isInstanceOf(ResponseStatusException.class)
      .hasMessageContaining("409");
    business.updateTicket(
      admin,
      id,
      "RESOLVED",
      "已安排安装核验，请等待专员联系。",
      "",
      1
    );
    assertThat(
      business
        .messages(actor, conv)
        .stream()
        .anyMatch(m -> "human".equals(m.get("role")))
    ).isTrue();
    assertThat(business.tickets(actor).get(0)).doesNotContainKey("notes");
  }

  @Test
  void workerToolScopesAreEnforced() {
    assertThatThrownBy(() ->
      tools.call(
        "ORDER",
        actor,
        "createRefundDraft",
        Map.of("orderId", orderId, "reason", "测试")
      )
    ).isInstanceOf(SecurityException.class);
  }

  @Test
  void chineseSemanticRetrievalHasTraceableEvidence() {
    var results = knowledge.search("门锁没电了怎么应急开门？", 4);
    assertThat(results).isNotEmpty();
    assertThat(
      results
        .stream()
        .anyMatch(
          c ->
            c.get("content").toString().contains("Type-C") &&
            Boolean.TRUE.equals(c.get("accepted"))
        )
    ).isTrue();
    var unsupported = knowledge.search("量子纠缠和黑洞霍金辐射的数学公式", 4);
    assertThat(
      unsupported
        .stream()
        .noneMatch(c -> Boolean.TRUE.equals(c.get("accepted")))
    ).isTrue();
  }

  @Test
  void checkpointSurvivesNewSaverInstance() {
    String thread = "run-test-" + UUID.randomUUID();
    var config = RunnableConfig.builder().threadId(thread).build();
    var cp = Checkpoint.builder()
      .id("cp-1")
      .nodeId("supervisor")
      .nextNodeId("product")
      .state(Map.of("routes", List.of("PRODUCT"), "userId", actor.userId()))
      .build();
    saver.put(config, cp);
    var restored = new JdbcCheckpointSaver(db).get(config).orElseThrow();
    assertThat(restored.getNextNodeId()).isEqualTo("product");
    assertThat(restored.getState().get("userId")).isEqualTo(actor.userId());
  }

  @Test
  void graphRunsThroughMcpAndRefusesUnsupportedQuestion() {
    String conv = business.newConversation(actor).get("id").toString();
    var run = journal.create(actor, conv, "门锁没电了怎么应急开门？", "r1");
    var result = graph.execute(run.get("id").toString());
    journal.finish(run.get("id").toString(), result);
    assertThat(result.get("answer").toString()).contains("Type-C");
    assertThat(
      journal
        .events(run.get("id").toString(), 0)
        .stream()
        .anyMatch(e -> "tool".equals(e.get("type")))
    ).isTrue();
    var unknown = journal.create(
      actor,
      conv,
      "给我量子纠缠和黑洞霍金辐射的公式",
      "r2"
    );
    var refused = graph.execute(unknown.get("id").toString());
    assertThat(refused.get("needsEscalation")).isEqualTo(true);
  }

  @Test
  void signedTokenTamperingAndAnonymousRequestsAreRejected() {
    String token = access.issue("customer").get("token").toString();
    assertThatThrownBy(() ->
      access.parse(token.substring(0, token.length() - 1) + "!")
    ).isInstanceOf(ResponseStatusException.class);
    assertThat(
      http.getForEntity("/api/orders", String.class).getStatusCode()
    ).isEqualTo(HttpStatus.UNAUTHORIZED);
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    assertThat(
      http
        .exchange(
          "/api/orders/SS20261010004",
          HttpMethod.GET,
          new HttpEntity<>(headers),
          String.class
        )
        .getStatusCode()
    ).isEqualTo(HttpStatus.FORBIDDEN);
  }
}
