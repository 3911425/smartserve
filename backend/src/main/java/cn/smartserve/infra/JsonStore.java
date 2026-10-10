package cn.smartserve.infra;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class JsonStore {

  public final JdbcTemplate jdbc;
  public final ObjectMapper mapper;
  private static final Set<String> TABLES = Set.of(
    "product",
    "customer_order",
    "conversation",
    "escalation_ticket"
  );

  public JsonStore(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("JSON encoding failed", e);
    }
  }

  public Map<String, Object> map(String value) {
    try {
      return mapper.readValue(value, new TypeReference<>() {});
    } catch (Exception e) {
      throw new IllegalStateException("JSON decoding failed", e);
    }
  }

  private String table(String table) {
    if (!TABLES.contains(table)) throw new IllegalArgumentException(
      "Unknown table"
    );
    return table;
  }

  public Optional<Map<String, Object>> find(String table, String id) {
    return jdbc
      .query(
        "SELECT payload,version FROM " + table(table) + " WHERE id=?",
        (rs, n) -> {
          var m = map(rs.getString(1));
          m.put("version", rs.getInt(2));
          return m;
        },
        id
      )
      .stream()
      .findFirst();
  }

  public Map<String, Object> require(String table, String id) {
    return find(table, id).orElseThrow(() ->
      new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在")
    );
  }

  public List<Map<String, Object>> list(String table, String owner) {
    return jdbc.query(
      "SELECT payload,version FROM " +
        table(table) +
        (owner == null ? "" : " WHERE owner_id=?") +
        " ORDER BY id",
      (rs, n) -> {
        var m = map(rs.getString(1));
        m.put("version", rs.getInt(2));
        return m;
      },
      owner == null ? new Object[] {} : new Object[] { owner }
    );
  }

  public void insert(
    String table,
    String id,
    String owner,
    Map<String, Object> payload
  ) {
    jdbc.update(
      "INSERT INTO " + table(table) + "(id,owner_id,payload) VALUES(?,?,?)",
      id,
      owner,
      json(payload)
    );
  }

  public void update(
    String table,
    String id,
    Map<String, Object> payload,
    int expected
  ) {
    int changed = jdbc.update(
      "UPDATE " +
        table(table) +
        " SET payload=?,version=version+1 WHERE id=? AND version=?",
      json(payload),
      id,
      expected
    );
    if (changed != 1) throw new ResponseStatusException(
      HttpStatus.CONFLICT,
      "记录已被其他请求修改，请刷新重试"
    );
  }

  public static String id(String prefix) {
    return (
      prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 16)
    );
  }
}
