package cn.smartserve;

import static org.assertj.core.api.Assertions.assertThat;

import cn.smartserve.agent.RunDispatcher;
import cn.smartserve.agent.RunJournal;
import cn.smartserve.business.BusinessService;
import cn.smartserve.rag.KnowledgeService;
import cn.smartserve.security.Access.Actor;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

@EnabledIfEnvironmentVariable(
  named = "SMARTSERVE_STANDARD_TEST",
  matches = "true"
)
@SpringBootTest(
  webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
  properties = {
    "server.port=18081",
    "spring.profiles.active=postgres",
    "smartserve.llm-enabled=false",
    "smartserve.tracing-enabled=false",
    "smartserve.data-path=../data",
    "smartserve.async-mode=rabbit",
    "smartserve.run-queue=${SMARTSERVE_TEST_QUEUE:smartserve.test.runs}",
    "smartserve.bootstrap-admin-password=StandardTest-Only-2026",
    "smartserve.bootstrap-customer-password=StandardCustomer-2026",
  }
)
class StandardStackIntegrationTest {

  @Autowired
  BusinessService business;

  @Autowired
  KnowledgeService knowledge;

  @Autowired
  RunJournal journal;

  @Autowired
  RunDispatcher dispatcher;

  @Autowired
  StringRedisTemplate redis;

  @Test
  void postgresVectorsAndRedisCacheAreActive() {
    assertThat(knowledge.stats().get("vectorStore")).isEqualTo(
      "PostgreSQL / pgvector"
    );
    assertThat(
      business
        .database()
        .jdbc.queryForObject(
          "SELECT COUNT(*) FROM knowledge_chunk WHERE vector_dims(embedding)=512",
          Integer.class
        )
    ).isGreaterThan(0);
    assertThat(knowledge.search("温湿度计需要网关吗？", 4)).isNotEmpty();
    String key = "smartserve:test:" + UUID.randomUUID();
    redis.opsForValue().set(key, "verified", Duration.ofSeconds(60));
    assertThat(redis.opsForValue().get(key)).isEqualTo("verified");
  }

  @Test
  void rabbitWorkerCompletesDurableTaskThroughMcp() throws Exception {
    Actor actor = new Actor("standard-test-" + UUID.randomUUID(), false);
    String conv = business.newConversation(actor).get("id").toString();
    String id = journal
      .create(
        actor,
        conv,
        "门锁没电了怎么应急开门？",
        UUID.randomUUID().toString()
      )
      .get("id")
      .toString();
    dispatcher.submit(id);
    long until = System.nanoTime() + Duration.ofSeconds(45).toNanos();
    while (
      System.nanoTime() < until &&
      !java.util.Set.of("COMPLETED", "FAILED").contains(
        journal.raw(id).get("status")
      )
    ) Thread.sleep(100);
    assertThat(journal.raw(id).get("status")).isEqualTo("COMPLETED");
    assertThat(journal.raw(id).get("answer").toString()).contains("Type-C");
    assertThat(
      business
        .database()
        .jdbc.queryForObject(
          "SELECT COUNT(*) FROM graph_checkpoint WHERE thread_id=?",
          Integer.class,
          id
        )
    ).isGreaterThan(0);
  }
}
