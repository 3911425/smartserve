package cn.smartserve.business;

import cn.smartserve.infra.JsonStore;
import cn.smartserve.security.Access;
import cn.smartserve.security.Access.Actor;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** All simulated money movements are database transactions, never model side effects. */
@Service
public class CommerceService {

  private final JsonStore db;
  private final FulfillmentSimulator fulfillment;

  public CommerceService(JsonStore db, FulfillmentSimulator fulfillment) {
    this.db = db;
    this.fulfillment = fulfillment;
  }

  public record Purchase(
    String productId,
    int quantity,
    String recipient,
    String phone,
    String address,
    String requestKey
  ) {}

  private ResponseStatusException error(HttpStatus status, String message) {
    return new ResponseStatusException(status, message);
  }

  private void lockCustomer(String id) {
    if (
      db.jdbc
        .queryForList("SELECT id FROM user_account WHERE id=? FOR UPDATE", id)
        .isEmpty()
    ) throw error(HttpStatus.NOT_FOUND, "客户不存在");
  }

  private Map<String, Object> row(String id, boolean lock) {
    var rows = db.jdbc.queryForList(
      "SELECT * FROM shop_order WHERE id=?" + (lock ? " FOR UPDATE" : ""),
      id
    );
    if (rows.isEmpty()) throw error(HttpStatus.NOT_FOUND, "订单不存在");
    return rows.get(0);
  }

  @Transactional
  public void migrateLegacyOrders() {
    for (var p : db.list("product", null))
      if (
        db.jdbc.queryForObject(
          "SELECT COUNT(*) FROM product_inventory WHERE product_id=?",
          Integer.class,
          p.get("id")
        ) ==
        0
      ) db.jdbc.update(
        "INSERT INTO product_inventory(product_id,available) VALUES(?,?)",
        p.get("id"),
        ((Number) p.getOrDefault("stock", 128)).intValue()
      );
    for (var old : db.list("customer_order", null)) {
      String id = old.get("id").toString();
      if (
        db.jdbc.queryForObject(
          "SELECT COUNT(*) FROM shop_order WHERE id=?",
          Integer.class,
          id
        ) >
        0
      ) continue;
      String product = old.get("productId").toString();
      var p = db.require("product", product);
      Timestamp created = parseDate(old.get("createdAt"));
      Timestamp delivered = parseDate(old.get("deliveredAt"));
      db.jdbc.update(
        "INSERT INTO shop_order(id,user_id,request_key,request_hash,status,amount,funding_source,recipient,phone,address,tracking_number,created_at,delivered_at) VALUES(?,?,?,?,?,?,'LEGACY_DEMO',?,?,?,?,?,?)",
        id,
        old.get("userId"),
        "legacy:" + id,
        BusinessService.hash(id),
        old.get("status"),
        new BigDecimal(old.get("amount").toString()),
        "历史演示客户",
        "",
        Objects.toString(old.get("address"), "历史演示地址"),
        Objects.toString(old.get("trackingNumber"), ""),
        created == null ? Timestamp.from(Instant.now()) : created,
        delivered
      );
      db.jdbc.update(
        "INSERT INTO order_item(order_id,product_id,product_name,unit_price,quantity,image) VALUES(?,?,?,?,?,?)",
        id,
        product,
        p.get("name"),
        new BigDecimal(p.get("price").toString()),
        ((Number) old.getOrDefault("quantity", 1)).intValue(),
        p.get("image")
      );
      event(
        id,
        old.get("status").toString(),
        "历史演示订单已迁移；未扣钱包，售后不会向钱包增加余额",
        "migration"
      );
    }
  }

  private Timestamp parseDate(Object value) {
    try {
      String text = Objects.toString(value, "");
      return text.isBlank()
        ? null
        : Timestamp.from(
            LocalDate.parse(text.substring(0, 10))
              .atStartOfDay(ZoneId.of("Asia/Shanghai"))
              .toInstant()
          );
    } catch (Exception e) {
      return null;
    }
  }

  public List<Map<String, Object>> products() {
    return db
      .list("product", null)
      .stream()
      .map(p -> {
        var copy = new LinkedHashMap<>(p);
        copy.put(
          "stock",
          db.jdbc.queryForObject(
            "SELECT available FROM product_inventory WHERE product_id=?",
            Integer.class,
            p.get("id")
          )
        );
        copy.remove("version");
        return (Map<String, Object>) copy;
      })
      .toList();
  }

  public Map<String, Object> wallet(Actor actor) {
    var rows = db.jdbc.queryForList(
      "SELECT balance,version FROM wallet WHERE user_id=?",
      actor.userId()
    );
    if (rows.isEmpty()) throw error(HttpStatus.NOT_FOUND, "该账号没有客户钱包");
    return Map.of(
      "balance",
      rows.get(0).get("balance"),
      "currency",
      "CNY",
      "simulated",
      true,
      "ledger",
      db.jdbc.queryForList(
        "SELECT id,kind,order_id,amount,balance_after,created_at FROM wallet_ledger WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT 50",
        actor.userId()
      )
    );
  }

  @Transactional
  public Map<String, Object> purchase(Actor actor, Purchase in) {
    if (actor.admin()) throw error(
      HttpStatus.FORBIDDEN,
      "管理员工作账号不参与购买，请使用客户账号"
    );
    if (
      in.quantity() < 1 ||
      in.quantity() > 20 ||
      in.recipient().isBlank() ||
      in.phone().isBlank() ||
      in.address().isBlank()
    ) throw error(HttpStatus.BAD_REQUEST, "请完善购买数量和收货信息");
    lockCustomer(actor.userId());
    String hash = BusinessService.hash(
      db.json(
        List.of(
          in.productId(),
          in.quantity(),
          in.recipient().strip(),
          in.phone().strip(),
          in.address().strip()
        )
      )
    );
    var existing = db.jdbc.queryForList(
      "SELECT id,request_hash FROM shop_order WHERE user_id=? AND request_key=?",
      actor.userId(),
      in.requestKey()
    );
    if (!existing.isEmpty()) {
      if (!hash.equals(existing.get(0).get("request_hash"))) throw error(
        HttpStatus.CONFLICT,
        "相同请求编号不能对应不同购买内容"
      );
      return order(actor, existing.get(0).get("id").toString());
    }
    var p = db.require("product", in.productId());
    BigDecimal price = new BigDecimal(p.get("price").toString());
    BigDecimal amount = price.multiply(BigDecimal.valueOf(in.quantity()));
    if (
      db.jdbc.update(
        "UPDATE product_inventory SET available=available-?,version=version+1 WHERE product_id=? AND available>=?",
        in.quantity(),
        in.productId(),
        in.quantity()
      ) !=
      1
    ) throw error(HttpStatus.CONFLICT, "库存不足，请调整数量");
    if (
      db.jdbc.update(
        "UPDATE wallet SET balance=balance-?,version=version+1 WHERE user_id=? AND balance>=?",
        amount,
        actor.userId(),
        amount
      ) !=
      1
    ) throw error(HttpStatus.CONFLICT, "模拟余额不足");
    String id =
      "SS" +
      String.format(
        "%015d",
        new java.math.BigInteger(
          UUID.randomUUID().toString().replace("-", ""),
          16
        ).mod(new java.math.BigInteger("1000000000000000"))
      );
    db.jdbc.update(
      "INSERT INTO shop_order(id,user_id,request_key,request_hash,status,amount,funding_source,recipient,phone,address) VALUES(?,?,?,?,'PAID',?,'WALLET',?,?,?)",
      id,
      actor.userId(),
      in.requestKey(),
      hash,
      amount,
      in.recipient().strip(),
      in.phone().strip(),
      in.address().strip()
    );
    db.jdbc.update(
      "INSERT INTO order_item(order_id,product_id,product_name,unit_price,quantity,image) VALUES(?,?,?,?,?,?)",
      id,
      in.productId(),
      p.get("name"),
      price,
      in.quantity(),
      p.get("image")
    );
    ledger(actor.userId(), id, "PAYMENT", amount.negate(), "payment:" + id);
    event(id, "PAID", "模拟余额支付成功，等待发货", actor.userId());
    String simulatedStatus = fulfillment.initialStatus();
    if (!Set.of("PAID", "SHIPPED", "DELIVERED").contains(simulatedStatus)) throw new IllegalStateException("Invalid simulated fulfillment status");
    if (!simulatedStatus.equals("PAID")) {
      db.jdbc.update(
        "UPDATE shop_order SET status='SHIPPED',tracking_number=?,shipped_at=CURRENT_TIMESTAMP WHERE id=?",
        "DEMO" + id.substring(2), id
      );
      event(id, "SHIPPED", "模拟场景：承运商已揽收，运输中", "simulation");
    }
    if (simulatedStatus.equals("DELIVERED")) {
      db.jdbc.update("UPDATE shop_order SET status='DELIVERED',delivered_at=CURRENT_TIMESTAMP WHERE id=?", id);
      event(id, "DELIVERED", "模拟场景：已签收，可体验签收后的售后流程", "simulation");
    }
    return order(actor, id);
  }

  private void ledger(
    String owner,
    String order,
    String kind,
    BigDecimal amount,
    String key
  ) {
    BigDecimal balance = db.jdbc.queryForObject(
      "SELECT balance FROM wallet WHERE user_id=?",
      BigDecimal.class,
      owner
    );
    db.jdbc.update(
      "INSERT INTO wallet_ledger(id,user_id,business_key,kind,order_id,amount,balance_after) VALUES(?,?,?,?,?,?,?)",
      JsonStore.id("ledger-"),
      owner,
      key,
      kind,
      order,
      amount,
      balance
    );
  }

  private void event(
    String id,
    String status,
    String description,
    String actor
  ) {
    db.jdbc.update(
      "INSERT INTO order_event(order_id,status,description,actor_id) VALUES(?,?,?,?)",
      id,
      status,
      description,
      actor
    );
  }

  public List<Map<String, Object>> orders(Actor actor) {
    return pageOrders(actor, actor.admin() ? "" : actor.userId(), "", 1).get(
          "items"
        ) instanceof
        List<?> list
      ? (List<Map<String, Object>>) list
      : List.of();
  }

  public Map<String, Object> pageOrders(
    Actor actor,
    String user,
    String status,
    int page
  ) {
    if (page < 1 || page > 10000 || (!status.isBlank() && !Set.of("PAID", "SHIPPED", "DELIVERED", "REFUND_PENDING", "REFUNDED", "CANCELLED").contains(status))) throw error(HttpStatus.BAD_REQUEST, "订单筛选状态或页码不合法");
    if (!actor.admin()) {
      if (!user.isBlank()) Access.own(actor, user);
      user = actor.userId();
    }
    String sql = "SELECT id FROM shop_order WHERE 1=1";
    var args = new ArrayList<Object>();
    if (!user.isBlank()) {
      sql += " AND user_id=?";
      args.add(user);
    }
    var counts = new LinkedHashMap<String, Object>();
    for (var count : db.jdbc.queryForList("SELECT status,COUNT(*) AS total FROM shop_order" + (user.isBlank() ? "" : " WHERE user_id=?") + " GROUP BY status", user.isBlank() ? new Object[] {} : new Object[] {user})) counts.put(count.get("status").toString(), count.get("total"));
    int total = status.isBlank() ? counts.values().stream().mapToInt(n -> ((Number) n).intValue()).sum() : ((Number) counts.getOrDefault(status, 0)).intValue();
    if (!status.isBlank()) {
      sql += " AND status=?";
      args.add(status);
    }
    sql += " ORDER BY created_at DESC,id DESC LIMIT 20 OFFSET ?";
    args.add(Math.min(10000, Math.max(0, page - 1)) * 20);
    var ids = db.jdbc.queryForList(sql, String.class, args.toArray());
    return Map.of(
      "items",
      ids
        .stream()
        .map(id -> order(actor, id))
        .toList(),
      "page",
      Math.max(1, page),
      "pageSize",
      20,
      "total", total,
      "hasNext", page * 20 < total,
      "summary", counts
    );
  }

  public Map<String, Object> order(Actor actor, String id) {
    var r = row(id, false);
    Access.own(actor, r.get("user_id").toString());
    var items = db.jdbc.queryForList(
      "SELECT product_id,product_name,unit_price,quantity,image FROM order_item WHERE order_id=? ORDER BY product_id",
      id
    );
    var result = new LinkedHashMap<String, Object>();
    result.put("id", id);
    result.put("userId", r.get("user_id"));
    result.put("status", r.get("status"));
    result.put("amount", r.get("amount"));
    result.put("fundingSource", r.get("funding_source"));
    result.put("simulated", true);
    result.put("version", r.get("version"));
    result.put("recipient", r.get("recipient"));
    result.put("phone", r.get("phone"));
    result.put("address", r.get("address"));
    result.put("trackingNumber", r.get("tracking_number"));
    result.put("createdAt", r.get("created_at").toString());
    result.put("shippedAt", r.get("shipped_at") == null ? "" : r.get("shipped_at").toString());
    result.put(
      "deliveredAt",
      r.get("delivered_at") == null
        ? ""
        : ((Timestamp) r.get("delivered_at")).toInstant()
            .atZone(ZoneId.of("Asia/Shanghai"))
            .toLocalDate()
            .toString()
    );
    result.put("items", items);
    if (!items.isEmpty()) {
      var item = items.get(0);
      result.put("productId", item.get("product_id"));
      result.put("productName", item.get("product_name"));
      result.put("quantity", item.get("quantity"));
      result.put("image", item.get("image"));
    }
    var events = db.jdbc.queryForList(
      "SELECT status,description,created_at FROM order_event WHERE order_id=? ORDER BY id",
      id
    );
    result.put("events", events);
    var logistics = events.stream().filter(e -> Set.of("PAID", "SHIPPED", "DELIVERED").contains(e.get("status"))).toList();
    result.put("logistics", logistics.isEmpty() ? "暂无物流事件" : logistics.get(logistics.size() - 1).get("description"));
    var refunds = db.jdbc.queryForList("SELECT id,status,amount,reason,review_note,version FROM refund_request WHERE order_id=?", id);
    for (var refund : refunds) refund.put("history", db.jdbc.queryForList("SELECT version,status,reason,review_note,created_at FROM refund_history WHERE refund_id=? ORDER BY version", refund.get("id")));
    result.put("refunds", refunds);
    result.put("serviceOptions", OrderServicePolicy.evaluate(r.get("status").toString(), (Timestamp) r.get("delivered_at"), refunds.isEmpty() ? "" : refunds.get(0).get("status").toString()));
    return result;
  }

  public Map<String, Object> toolOrder(Actor actor, String id) {
    var out = new LinkedHashMap<>(order(actor, id));
    out.remove("phone");
    out.remove("address");
    out.remove("recipient");
    return out;
  }

  @Transactional
  public Map<String, Object> advance(
    Actor admin,
    String id,
    String next,
    int expectedVersion
  ) {
    Access.admin(admin);
    var r = row(id, true);
    if (((Number) r.get("version")).intValue() != expectedVersion) throw error(
      HttpStatus.CONFLICT,
      "订单已更新，请刷新后重试"
    );
    String current = r.get("status").toString();
    if (
      !(current.equals("PAID") && next.equals("SHIPPED")) &&
      !(current.equals("SHIPPED") && next.equals("DELIVERED"))
    ) throw error(HttpStatus.CONFLICT, "仅允许待发货→运输中→已送达");
    if (next.equals("SHIPPED")) db.jdbc.update(
      "UPDATE shop_order SET status='SHIPPED',tracking_number=?,shipped_at=CURRENT_TIMESTAMP,version=version+1 WHERE id=?",
      "DEMO" + id.substring(2),
      id
    );
    else db.jdbc.update(
      "UPDATE shop_order SET status='DELIVERED',delivered_at=CURRENT_TIMESTAMP,version=version+1 WHERE id=?",
      id
    );
    event(
      id,
      next,
      next.equals("SHIPPED") ? "模拟承运商已揽收，运输中" : "模拟签收成功",
      admin.userId()
    );
    return order(admin, id);
  }

  @Transactional
  public Map<String, Object> cancel(Actor actor, String id) {
    requireCustomerCommand(actor);
    var old = row(id, false);
    Access.own(actor, old.get("user_id").toString());
    lockCustomer(old.get("user_id").toString());
    var r = row(id, true);
    if (r.get("status").equals("CANCELLED")) return order(actor, id);
    if (!r.get("status").equals("PAID")) throw error(
      HttpStatus.CONFLICT,
      "只有尚未发货的订单可以取消"
    );
    if (r.get("funding_source").equals("WALLET")) {
      db.jdbc.update(
        "UPDATE wallet SET balance=balance+?,version=version+1 WHERE user_id=?",
        r.get("amount"),
        r.get("user_id")
      );
      ledger(
        r.get("user_id").toString(),
        id,
        "CANCELLATION",
        new BigDecimal(r.get("amount").toString()),
        "cancel:" + id
      );
      restock(id);
    }
    db.jdbc.update(
      "UPDATE shop_order SET status='CANCELLED',version=version+1 WHERE id=?",
      id
    );
    event(id, "CANCELLED", r.get("funding_source").equals("WALLET") ? "订单已取消；模拟余额已原路退回，库存已恢复" : "历史演示订单已取消；不向钱包入账", actor.userId());
    return order(actor, id);
  }

  private void restock(String id) {
    for (var item : db.jdbc.queryForList(
      "SELECT product_id,quantity FROM order_item WHERE order_id=? ORDER BY product_id",
      id
    ))
      db.jdbc.update(
        "UPDATE product_inventory SET available=available+?,version=version+1 WHERE product_id=?",
        item.get("quantity"),
        item.get("product_id")
      );
  }

  public Map<String, Object> refundEligibility(Actor actor, String id) {
    var o = order(actor, id);
    var options = (Map<String, Object>) o.get("serviceOptions");
    var result = new LinkedHashMap<String, Object>(options);
    result.putAll(Map.of(
      "orderId",
      id,
      "eligible",
      Boolean.TRUE.equals(options.get("canApplyRefund")) || Boolean.TRUE.equals(options.get("canConfirmRefund")),
      "amount",
      o.get("amount"),
      "fundingSource",
      o.get("fundingSource"),
      "requiresConfirmation",
      true,
      "policy",
      "已送达且签收后7日内可申请；客户确认后等待管理员审核。历史演示订单不向钱包入账。",
      "paymentMode",
      "SIMULATED"
    ));
    result.put("orderStatus", o.get("status"));
    return result;
  }

  public Map<String, Object> prepareCancellation(Actor actor, String id) {
    var order = toolOrder(actor, id);
    return Map.of("orderId", id, "eligible", ((Map<?, ?>) order.get("serviceOptions")).get("canCancel"), "amount", order.get("amount"), "fundingSource", order.get("fundingSource"), "proposalOnly", true);
  }

  public Map<String, Object> prepareRefund(
    Actor actor,
    String id,
    String reason
  ) {
    var e = new LinkedHashMap<>(refundEligibility(actor, id));
    e.put("reason", reason);
    e.put("proposalOnly", true);
    return e;
  }

  @Transactional
  public Map<String, Object> createRefund(
    Actor actor,
    String id,
    String reason
  ) {
    requireCustomerCommand(actor);
    if (reason == null || reason.isBlank() || reason.length() > 500) throw error(HttpStatus.BAD_REQUEST, "售后原因需为1至500个字符");
    var old = row(id, false);
    Access.own(actor, old.get("user_id").toString());
    lockCustomer(old.get("user_id").toString());
    row(id, true);
    var existing = db.jdbc.queryForList(
      "SELECT id FROM refund_request WHERE order_id=?",
      id
    );
    if (!existing.isEmpty()) {
      var previous = refund(actor, existing.get(0).get("id").toString());
      if (!Set.of("REJECTED", "WITHDRAWN").contains(previous.get("status"))) return previous;
      if (!Boolean.TRUE.equals(refundEligibility(actor, id).get("canApplyRefund"))) throw error(HttpStatus.CONFLICT, "当前订单不能重新申请退货，请查看售后规则");
      db.jdbc.update("UPDATE refund_request SET status='AWAITING_CONFIRMATION',reason=?,confirmed_at=NULL,reviewed_at=NULL,reviewer_id=NULL,review_note='',version=version+1 WHERE id=?", reason, previous.get("id"));
      refundHistory(previous.get("id").toString(), actor.userId());
      event(id, "REFUND_DRAFT", "客户重新准备售后申请，尚待确认", actor.userId());
      return refund(actor, previous.get("id").toString());
    }
    if (
      !Boolean.TRUE.equals(refundEligibility(actor, id).get("eligible"))
    ) throw error(HttpStatus.CONFLICT, "不满足自动退货条件，可提交人工服务单");
    var o = order(actor, id);
    String owner = o.get("userId").toString();
    String refund = JsonStore.id("refund-");
    db.jdbc.update(
      "INSERT INTO refund_request(id,owner_id,order_id,idempotency_key,amount,status,reason) VALUES(?,?,?,?,?,'AWAITING_CONFIRMATION',?)",
      refund,
      owner,
      id,
      "full-refund:" + id,
      o.get("amount"),
      reason
    );
    event(id, "REFUND_DRAFT", "售后申请已准备，等待客户确认，尚未退款", actor.userId());
    refundHistory(refund, actor.userId());
    return refund(actor, refund);
  }

  public Map<String, Object> refund(Actor actor, String id) {
    var rows = db.jdbc.queryForList(
      "SELECT * FROM refund_request WHERE id=?",
      id
    );
    if (rows.isEmpty()) throw error(HttpStatus.NOT_FOUND, "退款申请不存在");
    var r = rows.get(0);
    Access.own(actor, r.get("owner_id").toString());
    var out = new LinkedHashMap<String, Object>();
    out.put("id", id);
    out.put("orderId", r.get("order_id"));
    out.put("userId", r.get("owner_id"));
    out.put("amount", r.get("amount"));
    out.put("status", r.get("status"));
    out.put("reason", r.get("reason"));
    out.put("reviewNote", r.get("review_note"));
    out.put("version", r.get("version"));
    out.put("paymentMode", "SIMULATED");
    out.put("history", db.jdbc.queryForList("SELECT version,status,reason,review_note,created_at FROM refund_history WHERE refund_id=? ORDER BY version", id));
    out.put(
      "fundingSource",
      row(r.get("order_id").toString(), false).get("funding_source")
    );
    return out;
  }

  @Transactional
  public Map<String, Object> confirmRefund(
    Actor actor,
    String id,
    boolean confirmed,
    int expectedVersion
  ) {
    requireCustomerCommand(actor);
    if (!confirmed) throw error(HttpStatus.BAD_REQUEST, "请明确确认申请信息");
    var first = refund(actor, id);
    String order = first.get("orderId").toString();
    lockCustomer(first.get("userId").toString());
    var o = row(order, true);
    var r = refund(actor, id);
    if (Set.of("PENDING_REVIEW", "REFUNDED").contains(r.get("status"))) return r;
    if (!r.get("status").equals("AWAITING_CONFIRMATION")) throw error(HttpStatus.CONFLICT, "申请已撤回或未通过，请重新准备后再确认");
    if (((Number) r.get("version")).intValue() != expectedVersion) throw error(HttpStatus.CONFLICT, "申请内容已更新，请重新核对后确认");
    if (
      !Boolean.TRUE.equals(refundEligibility(actor, order).get("eligible"))
    ) throw error(HttpStatus.CONFLICT, "订单状态已变化，请重新核验");
    db.jdbc.update(
      "UPDATE refund_request SET status='PENDING_REVIEW',confirmed_at=CURRENT_TIMESTAMP,version=version+1 WHERE id=?",
      id
    );
    db.jdbc.update(
      "UPDATE shop_order SET refund_previous_status=status,status='REFUND_PENDING',version=version+1 WHERE id=?",
      order
    );
    refundHistory(id, actor.userId());
    event(
      order,
      "REFUND_PENDING",
      "客户已确认申请，等待管理员审核",
      actor.userId()
    );
    return refund(actor, id);
  }

  @Transactional
  public Map<String, Object> withdrawRefund(Actor actor, String id, int expectedVersion) {
    requireCustomerCommand(actor);
    var first = refund(actor, id);
    lockCustomer(first.get("userId").toString());
    String orderId = first.get("orderId").toString();
    row(orderId, true);
    var current = refund(actor, id);
    if (current.get("status").equals("WITHDRAWN")) return current;
    if (((Number) current.get("version")).intValue() != expectedVersion) throw error(HttpStatus.CONFLICT, "申请已更新，请刷新后撤回");
    if (!Set.of("AWAITING_CONFIRMATION", "PENDING_REVIEW").contains(current.get("status"))) throw error(HttpStatus.CONFLICT, "仅待确认或待审核的申请可以撤回");
    if (current.get("status").equals("PENDING_REVIEW")) db.jdbc.update("UPDATE shop_order SET status=COALESCE(refund_previous_status,'DELIVERED'),refund_previous_status=NULL,version=version+1 WHERE id=?", orderId);
    db.jdbc.update("UPDATE refund_request SET status='WITHDRAWN',version=version+1 WHERE id=?", id);
    refundHistory(id, actor.userId());
    event(orderId, "REFUND_WITHDRAWN", "客户撤回售后申请；未发生退款", actor.userId());
    return refund(actor, id);
  }

  @Transactional
  public Map<String, Object> reviewRefund(
    Actor admin,
    String id,
    boolean approve,
    boolean returnedToStock,
    String note,
    int version
  ) {
    Access.admin(admin);
    var first = refund(admin, id);
    String order = first.get("orderId").toString();
    String owner = first.get("userId").toString();
    lockCustomer(owner);
    var o = row(order, true);
    var r = refund(admin, id);
    if (Set.of("REFUNDED", "REJECTED").contains(r.get("status"))) {
      if (!r.get("status").equals(approve ? "REFUNDED" : "REJECTED")) throw error(HttpStatus.CONFLICT, "已完成的审核不能改为相反结论");
      return r;
    }
    if (
      !r.get("status").equals("PENDING_REVIEW") || !o.get("status").equals("REFUND_PENDING") ||
      ((Number) r.get("version")).intValue() != version
    ) throw error(HttpStatus.CONFLICT, "申请不在待审核状态或已被更新");
    if (note.isBlank()) throw error(HttpStatus.BAD_REQUEST, "请填写审核说明");
    if (!approve && returnedToStock) throw error(HttpStatus.BAD_REQUEST, "驳回申请不能同时确认退回入库");
    if (approve) {
      if (o.get("funding_source").equals("WALLET")) {
        db.jdbc.update(
          "UPDATE wallet SET balance=balance+?,version=version+1 WHERE user_id=?",
          o.get("amount"),
          owner
        );
        ledger(
          owner,
          order,
          "REFUND",
          new BigDecimal(o.get("amount").toString()),
          "refund:" + order
        );
        if (returnedToStock) restock(order);
      }
      db.jdbc.update(
        "UPDATE shop_order SET status='REFUNDED',version=version+1 WHERE id=?",
        order
      );
    } else db.jdbc.update(
      "UPDATE shop_order SET status=COALESCE(refund_previous_status,'DELIVERED'),refund_previous_status=NULL,version=version+1 WHERE id=?",
      order
    );
    db.jdbc.update(
      "UPDATE refund_request SET status=?,reviewed_at=CURRENT_TIMESTAMP,reviewer_id=?,review_note=?,version=version+1 WHERE id=?",
      approve ? "REFUNDED" : "REJECTED",
      admin.userId(),
      note,
      id
    );
    refundHistory(id, admin.userId());
    event(
      order,
      approve ? "REFUNDED" : "REFUND_REJECTED",
      approve
        ? (o.get("funding_source").equals("WALLET")
            ? "审核通过，模拟余额已原路退回"
            : "历史演示售后已完成，不增加钱包余额")
        : "退款申请未通过：" + note,
      admin.userId()
    );
    return refund(admin, id);
  }

  public List<Map<String, Object>> refunds(Actor actor) {
    String sql =
      "SELECT id FROM refund_request" +
      (actor.admin() ? "" : " WHERE owner_id=?") +
      " ORDER BY created_at DESC LIMIT 100";
    return db.jdbc
      .queryForList(
        sql,
        String.class,
        actor.admin() ? new Object[] {} : new Object[] { actor.userId() }
      )
      .stream()
      .map(id -> refund(actor, id))
      .toList();
  }

  private void requireCustomerCommand(Actor actor) {
    if (actor.admin()) throw error(
      HttpStatus.FORBIDDEN,
      "此操作须由客户本人确认，管理员仅负责核验与审核"
    );
  }

  private void refundHistory(String id, String actor) {
    db.jdbc.update("INSERT INTO refund_history(refund_id,version,status,reason,review_note,actor_id) SELECT id,version,status,reason,review_note,? FROM refund_request WHERE id=?", actor, id);
  }
}
