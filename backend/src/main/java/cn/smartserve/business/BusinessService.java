package cn.smartserve.business;

import cn.smartserve.infra.JsonStore;
import cn.smartserve.security.Access;
import cn.smartserve.security.Access.Actor;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BusinessService {

  public final JsonStore db;

  public BusinessService(JsonStore db) {
    this.db = db;
  }

  public JsonStore database() {
    return db;
  }

  public List<Map<String, Object>> products() {
    var order = List.of(
      "lock-s1",
      "camera-c2",
      "bulb-l1",
      "plug-p1",
      "hub-h1",
      "sensor-t1"
    );
    return db
      .list("product", null)
      .stream()
      .sorted(Comparator.comparingInt(p -> order.indexOf(p.get("id"))))
      .toList();
  }

  public List<Map<String, Object>> orders(Actor actor) {
    return db.list("customer_order", actor.admin() ? null : actor.userId());
  }

  public Map<String, Object> order(Actor actor, String id) {
    var m = db.require("customer_order", id);
    Access.own(actor, (String) m.get("userId"));
    return m;
  }

  public Map<String, Object> conversation(Actor actor, String id) {
    var c = db.require("conversation", id);
    Access.own(actor, (String) c.get("userId"));
    return c;
  }

  public Map<String, Object> newConversation(Actor actor) {
    String id = JsonStore.id("conv-");
    var c = new LinkedHashMap<String, Object>(
      Map.of(
        "id",
        id,
        "userId",
        actor.userId(),
        "title",
        "新的服务会话",
        "createdAt",
        Instant.now().toString()
      )
    );
    db.insert("conversation", id, actor.userId(), c);
    return c;
  }

  public List<Map<String, Object>> messages(Actor actor, String id) {
    conversation(actor, id);
    return db.jdbc.query(
      "SELECT payload FROM chat_message WHERE conversation_id=? ORDER BY created_at,id",
      (rs, n) -> db.map(rs.getString(1)),
      id
    );
  }

  public void message(
    String owner,
    String conversation,
    String role,
    String content,
    Map<String, Object> extra
  ) {
    String id = JsonStore.id("msg-");
    var m = new LinkedHashMap<String, Object>();
    m.put("id", id);
    m.put("role", role);
    m.put("content", content);
    m.put("createdAt", Instant.now().toString());
    m.putAll(extra);
    db.jdbc.update(
      "INSERT INTO chat_message(id,owner_id,conversation_id,payload) VALUES(?,?,?,?)",
      id,
      owner,
      conversation,
      db.json(m)
    );
  }

  public Map<String, Object> refundEligibility(Actor actor, String orderId) {
    var o = order(actor, orderId);
    boolean delivered = "DELIVERED".equals(o.get("status"));
    long days = delivered
      ? ChronoUnit.DAYS.between(
          LocalDate.parse((String) o.get("deliveredAt")),
          LocalDate.now(ZoneId.of("Asia/Shanghai"))
        )
      : 999;
    return Map.of(
      "orderId",
      orderId,
      "eligible",
      delivered && days >= 0 && days <= 7,
      "daysSinceDelivery",
      days,
      "amount",
      o.get("amount"),
      "policy",
      "签收后7日内，商品及包装完整且未影响二次销售，可申请无理由退货；安装损坏、定制、耗材等例外需人工核验。",
      "requiresConfirmation",
      true,
      "paymentMode",
      "SIMULATED"
    );
  }

  @Transactional
  public Map<String, Object> createRefund(
    Actor actor,
    String orderId,
    String reason
  ) {
    // Authorization precedes deduplication. Lock the order across all application instances.
    order(actor, orderId);
    db.jdbc.queryForList(
      "SELECT id FROM customer_order WHERE id=? FOR UPDATE",
      orderId
    );
    var existing = db.jdbc.queryForList(
      "SELECT id FROM refund_request WHERE order_id=?",
      orderId
    );
    if (!existing.isEmpty()) return refund(
      actor,
      (String) existing.get(0).get("id")
    );
    if (
      !Boolean.TRUE.equals(refundEligibility(actor, orderId).get("eligible"))
    ) throw new ResponseStatusException(
      HttpStatus.CONFLICT,
      "订单不满足自动退货条件，请提交服务升级单由人工核验"
    );
    String id = JsonStore.id("refund-");
    var o = order(actor, orderId);
    String owner = o.get("userId").toString();
    String key = hash(owner + "|" + orderId + "|FULL_REFUND_V1");
    db.jdbc.update(
      "INSERT INTO refund_request(id,owner_id,order_id,idempotency_key,amount,status,reason) VALUES(?,?,?,?,?,'AWAITING_CONFIRMATION',?)",
      id,
      owner,
      orderId,
      key,
      new BigDecimal(o.get("amount").toString()),
      reason
    );
    return refund(actor, id);
  }

  public Map<String, Object> refund(Actor actor, String id) {
    var list = db.jdbc.queryForList(
      "SELECT id,owner_id,order_id,amount,status,reason,created_at FROM refund_request WHERE id=?",
      id
    );
    if (list.isEmpty()) throw new ResponseStatusException(
      HttpStatus.NOT_FOUND,
      "退款申请不存在"
    );
    var r = list.get(0);
    Access.own(actor, (String) r.get("owner_id"));
    return Map.of(
      "id",
      r.get("id"),
      "orderId",
      r.get("order_id"),
      "amount",
      r.get("amount"),
      "status",
      r.get("status"),
      "reason",
      r.get("reason"),
      "paymentMode",
      "SIMULATED"
    );
  }

  @Transactional
  public Map<String, Object> confirmRefund(
    Actor actor,
    String id,
    boolean confirmed
  ) {
    if (!confirmed) throw new ResponseStatusException(
      HttpStatus.BAD_REQUEST,
      "请明确确认退款申请"
    );
    var r = refund(actor, id);
    String orderId = (String) r.get("orderId");
    db.jdbc.queryForList(
      "SELECT id FROM customer_order WHERE id=? FOR UPDATE",
      orderId
    );
    r = refund(actor, id);
    if ("APPROVED".equals(r.get("status"))) return r;
    var eligible = refundEligibility(actor, orderId);
    if (
      !Boolean.TRUE.equals(eligible.get("eligible"))
    ) throw new ResponseStatusException(
      HttpStatus.CONFLICT,
      "订单状态已变化，请重新核验"
    );
    var o = order(actor, orderId);
    o.put("status", "REFUND_PENDING");
    db.update(
      "customer_order",
      orderId,
      o,
      ((Number) o.get("version")).intValue()
    );
    db.jdbc.update(
      "UPDATE refund_request SET status='APPROVED' WHERE id=? AND status='AWAITING_CONFIRMATION'",
      id
    );
    return refund(actor, id);
  }

  @Transactional
  public Map<String, Object> createTicket(
    Actor actor,
    String conv,
    String orderId,
    String title,
    String details,
    String contact,
    String priority,
    String requestKey,
    boolean confirmed
  ) {
    var c = conversation(actor, conv);
    if (!confirmed) throw new ResponseStatusException(
      HttpStatus.BAD_REQUEST,
      "请确认表单信息后提交"
    );
    if (!orderId.isBlank()) order(actor, orderId);
    String id =
      "ticket-" +
      hash(actor.userId() + "|" + conv + "|" + requestKey).substring(0, 20);
    db.jdbc.queryForList(
      "SELECT id FROM conversation WHERE id=? FOR UPDATE",
      conv
    );
    var old = db.find("escalation_ticket", id);
    if (old.isPresent()) {
      Access.own(actor, (String) old.get().get("userId"));
      return publicTicket(actor, old.get());
    }
    var t = new LinkedHashMap<String, Object>();
    t.put("id", id);
    t.put("userId", c.get("userId"));
    t.put("conversationId", conv);
    t.put("orderId", orderId);
    t.put("title", title);
    t.put("details", details);
    t.put("contact", contact);
    t.put("priority", priority);
    t.put("status", "PENDING");
    t.put("createdAt", Instant.now().toString());
    t.put(
      "summary",
      messages(actor, conv)
        .stream()
        .filter(m -> "user".equals(m.get("role")))
        .map(m -> m.get("content").toString())
        .reduce((a, b) -> a + "；" + b)
        .orElse(details)
    );
    t.put("notes", new ArrayList<>());
    t.put("resolution", "");
    t.put("assignee", "");
    db.jdbc.update(
      "INSERT INTO escalation_ticket(id,owner_id,conversation_id,status,payload) VALUES(?,?,?,'PENDING',?)",
      id,
      c.get("userId"),
      conv,
      db.json(t)
    );
    message(
      (String) c.get("userId"),
      conv,
      "assistant",
      "服务升级单已提交，编号 " +
        id +
        "。人工专员会核验信息并在这里回复处理结果。",
      Map.of("ticketId", id)
    );
    return publicTicket(actor, t);
  }

  public List<Map<String, Object>> tickets(Actor actor) {
    return db
      .list("escalation_ticket", actor.admin() ? null : actor.userId())
      .stream()
      .map(t -> publicTicket(actor, t))
      .toList();
  }

  private Map<String, Object> publicTicket(Actor a, Map<String, Object> t) {
    if (a.admin()) return t;
    var copy = new LinkedHashMap<>(t);
    copy.remove("notes");
    copy.remove("summary");
    return copy;
  }

  @Transactional
  public Map<String, Object> updateTicket(
    Actor actor,
    String id,
    String status,
    String resolution,
    String note,
    int version
  ) {
    Access.admin(actor);
    var t = db.require("escalation_ticket", id);
    String current = (String) t.get("status");
    if (
      !Set.of("PROCESSING", "RESOLVED").contains(status) ||
      "RESOLVED".equals(current) ||
      ("RESOLVED".equals(status) && !"PROCESSING".equals(current))
    ) throw new ResponseStatusException(
      HttpStatus.CONFLICT,
      "请按待处理→处理中→已完成的顺序处理"
    );
    if (
      "RESOLVED".equals(status) && resolution.isBlank()
    ) throw new ResponseStatusException(
      HttpStatus.BAD_REQUEST,
      "完成时必须填写给用户的处理结果"
    );
    t.put("status", status);
    t.put("assignee", actor.userId());
    t.put("resolution", resolution);
    var notes = new ArrayList<Object>((List<?>) t.get("notes"));
    if (!note.isBlank()) notes.add(
      Map.of(
        "author",
        actor.userId(),
        "content",
        note,
        "at",
        Instant.now().toString()
      )
    );
    t.put("notes", notes);
    db.update("escalation_ticket", id, t, version);
    db.jdbc.update(
      "UPDATE escalation_ticket SET status=? WHERE id=?",
      status,
      id
    );
    if (status.equals("RESOLVED")) message(
      (String) t.get("userId"),
      (String) t.get("conversationId"),
      "human",
      "人工专员已完成服务升级单 " + id + "：\n" + resolution,
      Map.of("ticketId", id)
    );
    return db.require("escalation_ticket", id);
  }

  public static String hash(String text) {
    try {
      return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(
          text.getBytes(StandardCharsets.UTF_8)
        )
      );
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
