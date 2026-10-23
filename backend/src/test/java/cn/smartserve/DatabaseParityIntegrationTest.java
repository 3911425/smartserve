package cn.smartserve;

import java.sql.DriverManager;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Run the identical commerce/permission/Agent regression against each PG engine. */
@EnabledIfEnvironmentVariable(named = "SMARTSERVE_PARITY_TEST", matches = "true")
class DatabaseParityIntegrationTest extends SmartServeIntegrationTest {
  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    String url = System.getenv("SMARTSERVE_PARITY_DB_URL");
    if (url == null || !url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/smartserve_verify_[a-z0-9_]+")) throw new IllegalStateException("Parity tests require a dedicated smartserve_verify_* database");
    String database = url.substring(url.lastIndexOf('/') + 1);
    try (var connection = DriverManager.getConnection(url.substring(0, url.lastIndexOf('/') + 1) + "postgres", System.getenv("DB_USER"), System.getenv("DB_PASSWORD")); var statement = connection.createStatement()) {
      // Never reuse or clear a business database; the caller supplies a new verification name.
      statement.executeUpdate("CREATE DATABASE " + database);
    } catch (Exception error) { throw new IllegalStateException("Create a fresh isolated parity database failed", error); }
    registry.add("spring.datasource.url", () -> url);
    registry.add("spring.datasource.username", () -> System.getenv("DB_USER"));
    registry.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
    registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET TIME ZONE 'Asia/Shanghai'");
    registry.add("smartserve.pgvector-enabled", () -> Boolean.parseBoolean(System.getenv("SMARTSERVE_PARITY_PGVECTOR")));
  }
}
