package cn.smartserve.agent;

import cn.smartserve.business.BusinessService;
import cn.smartserve.infra.JsonStore;
import cn.smartserve.security.Access;
import cn.smartserve.security.Access.Actor;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RunJournal {

  public final JsonStore db;
  private final BusinessService business;

  public RunJournal(JsonStore db, BusinessService business) {
    this.db = db;
    this.business = business;
  }

  @Transactional
  public Map<String, Object> create(
    Actor actor,
    String conversation,
    String question,
    String requestKey
  ) {
    business.conversation(actor, conversation);
    db.jdbc.queryForList(
      "SELECT id FROM conversation WHERE id=? FOR UPDATE",
      conversation
    );
    String id =
      "run-" +
      BusinessService.hash(
        actor.userId() + "|" + conversation + "|" + requestKey
      ).substring(0, 24);
    var old = db.jdbc.queryForList("SELECT id FROM agent_run WHERE id=?", id);
    if (!old.isEmpty()) {
      var r = get(actor, id);
      if (
        !question.equals(r.get("question"))
      ) throw new ResponseStatusException(
        HttpStatus.CONFLICT,
        "同一请求键对应不同输入"
      );
      return r;
    }
    if (
      db.jdbc.queryForObject(
        "SELECT COUNT(*) FROM agent_run WHERE conversation_id=? AND status IN ('QUEUED','RUNNING')",
        Integer.class,
        conversation
      ) >
      0
    ) throw new ResponseStatusException(
      HttpStatus.CONFLICT,
      "当前会话仍有任务执行，请等待完成"
    );
    var run = new LinkedHashMap<String, Object>();
    run.put("id", id);
    run.put("userId", actor.userId());
    run.put("admin", actor.admin());
    run.put("conversationId", conversation);
    run.put("question", question);
    run.put("status", "QUEUED");
    run.put("createdAt", Instant.now().toString());
    run.put("traceId", UUID.randomUUID().toString());
    run.put("tokens", 0);
    db.jdbc.update(
      "INSERT INTO agent_run(id,owner_id,conversation_id,status,payload) VALUES(?,?,?,'QUEUED',?)",
      id,
      actor.userId(),
      conversation,
      db.json(run)
    );
    business.message(
      actor.userId(),
      conversation,
      "user",
      question,
      Map.of("runId", id)
    );
    event(
      id,
      "queued",
      Map.of("label", "任务已进入队列", "agent", "Supervisor", "status", "ok")
    );
    return run;
  }

  public JsonStore database() {
    return db;
  }

  public Map<String, Object> raw(String id) {
    return db.jdbc
      .query(
        "SELECT payload,status FROM agent_run WHERE id=?",
        (rs, n) -> {
          var r = db.map(rs.getString(1));
          r.put("status", rs.getString(2));
          return r;
        },
        id
      )
      .stream()
      .findFirst()
      .orElseThrow(() ->
        new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在")
      );
  }

  public Map<String, Object> get(Actor actor, String id) {
    var run = raw(id);
    Access.own(actor, (String) run.get("userId"));
    return run;
  }

  public List<Map<String, Object>> list(Actor actor) {
    return db.jdbc.query(
      "SELECT payload,status FROM agent_run" +
        (actor.admin() ? "" : " WHERE owner_id=?") +
        " ORDER BY created_at DESC LIMIT 100",
      (rs, n) -> {
        var r = db.map(rs.getString(1));
        r.put("status", rs.getString(2));
        return r;
      },
      actor.admin() ? new Object[] {} : new Object[] { actor.userId() }
    );
  }

  public void event(String run, String type, Map<String, Object> data) {
    var m = new LinkedHashMap<>(data);
    m.put("type", type);
    m.put("at", Instant.now().toString());
    db.jdbc.update(
      "INSERT INTO run_event(run_id,event_type,payload) VALUES(?,?,?)",
      run,
      type,
      db.json(m)
    );
    db.jdbc.update(
      "UPDATE agent_run SET updated_at=CURRENT_TIMESTAMP WHERE id=?",
      run
    );
  }

  public List<Map<String, Object>> events(String id, long after) {
    return db.jdbc.query(
      "SELECT id,payload FROM run_event WHERE run_id=? AND id>? ORDER BY id",
      (rs, n) -> {
        var m = db.map(rs.getString(2));
        m.put("eventId", rs.getLong(1));
        return m;
      },
      id,
      after
    );
  }

  @Transactional
  public void finish(String id, Map<String, Object> result) {
    var run = raw(id);
    run.putAll(result);
    run.put("status", "COMPLETED");
    run.put("completedAt", Instant.now().toString());
    db.jdbc.update(
      "UPDATE agent_run SET status='COMPLETED',payload=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
      db.json(run),
      id
    );
    business.message(
      (String) run.get("userId"),
      (String) run.get("conversationId"),
      "assistant",
      Objects.toString(result.get("answer"), ""),
      result
    );
    event(
      id,
      "completed",
      Map.of(
        "label",
        "回答已完成",
        "agent",
        "Guardrail",
        "status",
        "ok",
        "result",
        result
      )
    );
  }

  public void fail(String id) {
    var run = raw(id);
    run.put("status", "FAILED");
    run.put("error", "服务暂时无法完成，请重试或提交服务升级单");
    db.jdbc.update(
      "UPDATE agent_run SET status='FAILED',payload=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
      db.json(run),
      id
    );
    event(
      id,
      "failed",
      Map.of(
        "label",
        "任务未完成，可转人工",
        "agent",
        "Middleware",
        "status",
        "error"
      )
    );
  }
}
