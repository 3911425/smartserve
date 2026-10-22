package cn.smartserve.agent;

import cn.smartserve.business.BusinessService;
import cn.smartserve.infra.JsonStore;
import cn.smartserve.security.Access;
import cn.smartserve.security.Access.Actor;
import java.sql.Timestamp;
import java.time.*;
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

  public JsonStore database() {
    return db;
  }

  @Transactional
  public Map<String, Object> create(
    Actor actor,
    String conversation,
    String question,
    String key
  ) {
    business.conversation(actor, conversation);
    // Serialize the per-customer pending-task quota across different conversations.
    db.jdbc.queryForList(
      "SELECT id FROM user_account WHERE id=? FOR UPDATE",
      actor.userId()
    );
    db.jdbc.queryForList(
      "SELECT id FROM conversation WHERE id=? FOR UPDATE",
      conversation
    );
    String id =
      "run-" +
      BusinessService.hash(
        actor.userId() + "|" + conversation + "|" + key
      ).substring(0, 24);
    if (
      db.jdbc.queryForObject(
        "SELECT COUNT(*) FROM agent_run WHERE id=?",
        Integer.class,
        id
      ) >
      0
    ) {
      var old = get(actor, id);
      if (
        !question.equals(old.get("question"))
      ) throw new ResponseStatusException(
        HttpStatus.CONFLICT,
        "相同请求编号不能对应不同问题"
      );
      return old;
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
      "当前会话还有任务执行，请等待或取消"
    );
    if (
      db.jdbc.queryForObject(
        "SELECT COUNT(*) FROM agent_run WHERE owner_id=? AND status IN ('QUEUED','RUNNING')",
        Integer.class,
        actor.userId()
      ) >=
      3
    ) throw new ResponseStatusException(
      HttpStatus.TOO_MANY_REQUESTS,
      "你的待处理任务较多，请稍后再试"
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
      Map.of(
        "agent",
        "Queue",
        "label",
        "任务已持久化，等待执行",
        "status",
        "ok"
      )
    );
    return run;
  }

  public Map<String, Object> raw(String id) {
    return db.jdbc
      .query(
        "SELECT payload,status,attempts FROM agent_run WHERE id=?",
        (rs, n) -> {
          var r = db.map(rs.getString(1));
          r.put("status", rs.getString(2));
          r.put("executionAttempts", rs.getInt(3));
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
    var r = raw(id);
    Access.own(actor, r.get("userId").toString());
    r.putAll(usage(id));
    return r;
  }

  public List<Map<String, Object>> list(Actor actor) {
    return db.jdbc
      .queryForList(
        "SELECT id FROM agent_run" +
          (actor.admin() ? "" : " WHERE owner_id=?") +
          " ORDER BY created_at DESC LIMIT 100",
        String.class,
        actor.admin() ? new Object[] {} : new Object[] { actor.userId() }
      )
      .stream()
      .map(id -> get(actor, id))
      .toList();
  }

  public void event(String id, String type, Map<String, Object> data) {
    var value = new LinkedHashMap<>(data);
    value.put("type", type);
    value.put("at", Instant.now().toString());
    db.jdbc.update(
      "INSERT INTO run_event(run_id,event_type,payload) VALUES(?,?,?)",
      id,
      type,
      db.json(value)
    );
  }

  public List<Map<String, Object>> events(String id, long after) {
    return db.jdbc.query(
      "SELECT id,payload FROM run_event WHERE run_id=? AND id>? ORDER BY id",
      (rs, n) -> {
        var value = db.map(rs.getString(2));
        value.put("eventId", rs.getLong(1));
        return value;
      },
      id,
      after
    );
  }

  public Map<String, Object> usage(String id) {
    var r = db.jdbc.queryForMap(
      "SELECT COUNT(*) AS calls,COALESCE(SUM(input_tokens),0) AS input_count,COALESCE(SUM(output_tokens),0) AS output_count,COALESCE(SUM(CASE WHEN status='ERROR' THEN 1 ELSE 0 END),0) AS failed FROM model_call WHERE run_id=?",
      id
    );
    long input = ((Number) r.get("input_count")).longValue(),
      output = ((Number) r.get("output_count")).longValue();
    return Map.of(
      "modelCalls",
      r.get("calls"),
      "inputTokens",
      input,
      "outputTokens",
      output,
      "tokens",
      input + output,
      "failedModelCalls",
      r.get("failed")
    );
  }

  public List<Map<String, Object>> modelCalls(Actor actor, String id) {
    get(actor, id);
    return db.jdbc.queryForList(
      "SELECT id,node,purpose,model,attempt,status,input_tokens,output_tokens,duration_ms,error_code,created_at FROM model_call WHERE run_id=? ORDER BY created_at,id",
      id
    );
  }

  public String claim(String id) {
    String lease = UUID.randomUUID().toString();
    return db.jdbc.update(
        "UPDATE agent_run SET status='RUNNING',lease_token=?,lease_until=?,attempts=attempts+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='QUEUED' AND attempts<3",
        lease,
        Timestamp.from(Instant.now().plusSeconds(90)),
        id
      ) ==
      1
      ? lease
      : null;
  }

  public boolean heartbeat(String id, String lease) {
    return (
      db.jdbc.update(
        "UPDATE agent_run SET lease_until=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='RUNNING' AND lease_token=?",
        Timestamp.from(Instant.now().plusSeconds(90)),
        id,
        lease
      ) ==
      1
    );
  }

  @Transactional
  public void finish(String id, Map<String, Object> result, String lease) {
    ExecutionScope.check(db, true);
    var run = raw(id);
    var finalResult = new LinkedHashMap<>(result);
    finalResult.put("runId", id);
    finalResult.putAll(usage(id));
    run.putAll(finalResult);
    run.put("completedAt", Instant.now().toString());
    run.put("status", "COMPLETED");
    if (
      db.jdbc.update(
        "UPDATE agent_run SET status='COMPLETED',payload=?,lease_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='RUNNING' AND lease_token=?",
        db.json(run),
        id,
        lease
      ) !=
      1
    ) throw new ExecutionScope.StaleExecutionException();
    business.message(
      run.get("userId").toString(),
      run.get("conversationId").toString(),
      "assistant",
      Objects.toString(finalResult.get("answer"), ""),
      finalResult
    );
    event(
      id,
      "completed",
      Map.of(
        "agent",
        "Result",
        "label",
        "回答已保存",
        "status",
        "ok",
        "result",
        finalResult
      )
    );
  }

  public void fail(String id, String lease) {
    var r = raw(id);
    r.put("error", "任务暂时无法完成，可以重试或提交人工服务单");
    if (
      db.jdbc.update(
        "UPDATE agent_run SET status='FAILED',payload=?,lease_token=NULL,lease_until=NULL WHERE id=? AND status='RUNNING' AND lease_token=?",
        db.json(r),
        id,
        lease
      ) ==
      1
    ) event(
      id,
      "failed",
      Map.of(
        "agent",
        "Runtime",
        "label",
        "执行失败，已停止本次任务",
        "status",
        "error"
      )
    );
  }

  @Transactional
  public Map<String, Object> cancel(Actor actor, String id) {
    get(actor, id);
    if (
      db.jdbc.update(
        "UPDATE agent_run SET status='CANCELLED',lease_token=NULL,lease_until=NULL WHERE id=? AND status IN ('QUEUED','RUNNING')",
        id
      ) >
      0
    ) event(
      id,
      "cancelled",
      Map.of(
        "agent",
        "Runtime",
        "label",
        "任务已取消；已发送的模型请求仍可能计费",
        "status",
        "warning"
      )
    );
    return get(actor, id);
  }

  @Transactional
  public Object feedback(
    Actor actor,
    String id,
    boolean helpful,
    String comment
  ) {
    var r = get(actor, id);
    if (
      !actor.userId().equals(r.get("userId"))
    ) throw new ResponseStatusException(
      HttpStatus.FORBIDDEN,
      "只能反馈自己的回答"
    );
    if (!r.get("status").equals("COMPLETED")) throw new ResponseStatusException(
      HttpStatus.CONFLICT,
      "回答完成后才能反馈"
    );
    db.jdbc.queryForList("SELECT id FROM agent_run WHERE id=? FOR UPDATE", id);
    if (
      db.jdbc.update(
        "UPDATE answer_feedback SET helpful=?,comment=?,review_status='NEW',review_note='' WHERE run_id=?",
        helpful,
        comment,
        id
      ) ==
      0
    ) db.jdbc.update(
      "INSERT INTO answer_feedback(run_id,user_id,helpful,comment) VALUES(?,?,?,?)",
      id,
      actor.userId(),
      helpful,
      comment
    );
    return Map.of("ok", true);
  }

  public Object feedbackList(Actor admin) {
    Access.admin(admin);
    return db.jdbc.queryForList(
      "SELECT f.*,r.payload AS run_payload FROM answer_feedback f JOIN agent_run r ON r.id=f.run_id ORDER BY f.created_at DESC LIMIT 100"
    );
  }

  public Object reviewFeedback(Actor admin, String id, String note) {
    Access.admin(admin);
    if (
      db.jdbc.update(
        "UPDATE answer_feedback SET review_status='REVIEWED',review_note=? WHERE run_id=?",
        note,
        id
      ) !=
      1
    ) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "反馈不存在");
    return Map.of("ok", true);
  }
}
