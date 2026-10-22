package cn.smartserve.agent;

import cn.smartserve.infra.JsonStore;
import java.util.*;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.*;
import org.springframework.stereotype.Component;

/** Native LangGraph4j saver backed by JDBC; the same implementation works with PG and demo H2. */
@Component
public class JdbcCheckpointSaver implements BaseCheckpointSaver {

  private final JsonStore db;

  public JdbcCheckpointSaver(JsonStore db) {
    this.db = db;
  }

  @Override
  public Collection<Checkpoint> list(RunnableConfig config) {
    return db.jdbc.query(
      "SELECT payload FROM graph_checkpoint WHERE thread_id=? ORDER BY created_at DESC,checkpoint_id DESC",
      (rs, n) -> decode(rs.getString(1)),
      threadId(config)
    );
  }

  private Checkpoint decode(String json) {
    var m = db.map(json);
    return Checkpoint.builder()
      .id((String) m.get("id"))
      .state((Map<String, Object>) m.get("state"))
      .nodeId((String) m.get("nodeId"))
      .nextNodeId((String) m.get("nextNodeId"))
      .build();
  }

  @Override
  public Optional<Checkpoint> get(RunnableConfig config) {
    if (config.checkPointId().isPresent()) return db.jdbc
      .query(
        "SELECT payload FROM graph_checkpoint WHERE thread_id=? AND checkpoint_id=?",
        (rs, n) -> decode(rs.getString(1)),
        threadId(config),
        config.checkPointId().get()
      )
      .stream()
      .findFirst();
    return list(config).stream().findFirst();
  }

  @Override
  @org.springframework.transaction.annotation.Transactional
  public RunnableConfig put(RunnableConfig config, Checkpoint cp) {
    ExecutionScope.check(db, true);
    String json = db.json(
      Map.of(
        "id",
        cp.getId(),
        "state",
        cp.getState(),
        "nodeId",
        cp.getNodeId(),
        "nextNodeId",
        cp.getNextNodeId()
      )
    );
    int n = db.jdbc.update(
      "UPDATE graph_checkpoint SET payload=?,created_at=CURRENT_TIMESTAMP WHERE thread_id=? AND checkpoint_id=?",
      json,
      threadId(config),
      cp.getId()
    );
    if (n == 0) db.jdbc.update(
      "INSERT INTO graph_checkpoint(thread_id,checkpoint_id,payload) VALUES(?,?,?)",
      threadId(config),
      cp.getId(),
      json
    );
    return RunnableConfig.builder(config).checkPointId(cp.getId()).build();
  }

  @Override
  public Tag release(RunnableConfig config) {
    return new Tag(threadId(config), list(config));
  }
}
