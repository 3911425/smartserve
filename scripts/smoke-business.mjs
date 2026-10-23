import assert from "node:assert/strict";
import { Client, environment, save } from "./evaluation-client.mjs";
const env = await environment(),
  admin = new Client();
await admin.api("/auth/login", "POST", {
  username: env.BOOTSTRAP_ADMIN_USER || "admin",
  password: env.BOOTSTRAP_ADMIN_PASSWORD,
});
const client = await new Client().fixture(),
  stranger = await new Client().fixture(),
  checks = [];
async function check(name, action) {
  await action();
  checks.push({ name, passed: true });
  console.log("PASS", name);
}
let order, refund, conversation, ticket;
await check("initial simulated grant", async () =>
  assert.equal(Number((await client.api("/wallet")).balance), 10000),
);
await check("profile and default address", async () => {
  const me = await client.api("/auth/profile", "PUT", {
    displayName: "闭环测试客户",
    phone: "00000000000",
    shippingAddress: "隔离评测地址",
  });
  assert.equal(me.displayName, "闭环测试客户");
});
const products = await client.api("/products"),
  stock = products.find((p) => p.id === "plug-p1").stock;
await check(
  "16 identical HTTP checkouts have one order and one debit",
  async () => {
    const body = {
      productId: "plug-p1",
      quantity: 1,
      recipient: "模拟收件人",
      phone: "00000000000",
      address: "隔离测试地址",
      requestKey: crypto.randomUUID(),
    };
    const out = await Promise.all(
      Array.from({ length: 16 }, () => client.api("/purchases", "POST", body)),
    );
    assert.equal(new Set(out.map((o) => o.id)).size, 1);
    order = out[0];
    assert.equal(Number((await client.api("/wallet")).balance), 9941);
    assert.equal(
      (await client.api("/products")).find((p) => p.id === "plug-p1").stock,
      stock - 1,
    );
  },
);
await check("cross-user order rejected", async () => {
  await assert.rejects(
    stranger.api("/orders/" + order.id),
    (e) => e.status === 403,
  );
});
await check("customer cannot advance shipping", async () => {
  await assert.rejects(
    client.api("/admin/orders/" + order.id + "/advance", "POST", {
      status: "SHIPPED",
      version: order.version,
    }),
    (e) => e.status === 403,
  );
});
await check("admin ships then delivers with persisted version", async () => {
  if (order.status === "PAID") order = await admin.api("/admin/orders/" + order.id + "/advance", "POST", {
    status: "SHIPPED",
    version: order.version,
  });
  if (order.status === "SHIPPED") order = await admin.api("/admin/orders/" + order.id + "/advance", "POST", {
    status: "DELIVERED",
    version: order.version,
  });
  assert.equal(order.status, "DELIVERED");
  assert.ok(order.trackingNumber);
});
await check("refund requires user confirmation and admin review", async () => {
  refund = await client.api("/refunds", "POST", {
    orderId: order.id,
    reason: "评测模拟退货",
  });
  assert.equal(refund.status, "AWAITING_CONFIRMATION");
  await assert.rejects(
    admin.api("/admin/refunds/" + refund.id + "/review", "POST", {
      approve: true,
      returnedToStock: true,
      note: "不能提前审核",
      version: refund.version,
    }),
    (e) => e.status === 409,
  );
  refund = await client.api("/refunds/" + refund.id + "/confirm", "POST", {
    confirmed: true,
    version: refund.version,
  });
  assert.equal(refund.status, "PENDING_REVIEW");
  assert.equal(Number((await client.api("/wallet")).balance), 9941);
});
await check(
  "concurrent repeated admin review refunds exactly once",
  async () => {
    await Promise.all(
      Array.from({ length: 8 }, () =>
        admin.api("/admin/refunds/" + refund.id + "/review", "POST", {
          approve: true,
          returnedToStock: true,
          note: "评测已核验退回入库",
          version: refund.version,
        }),
      ),
    );
    const wallet = await client.api("/wallet");
    assert.equal(Number(wallet.balance), 10000);
    assert.equal(
      wallet.ledger.filter(
        (l) => l.kind === "REFUND" && l.order_id === order.id,
      ).length,
      1,
    );
    assert.equal(
      (await client.api("/products")).find((p) => p.id === "plug-p1").stock,
      stock,
    );
  },
);
await check(
  "confirmed ticket reaches admin and resolution returns to conversation",
  async () => {
    conversation = await client.api("/conversations", "POST");
    ticket = await client.api("/tickets", "POST", {
      conversationId: conversation.id,
      orderId: order.id,
      title: "隔离测试服务单",
      details: "验证人工处理回写",
      contact: "模拟联系方式",
      priority: "NORMAL",
      confirmed: true,
      requestKey: crypto.randomUUID(),
    });
    assert.equal(ticket.status, "PENDING");
    ticket = await admin.api("/tickets/" + ticket.id, "PUT", {
      status: "PROCESSING",
      version: ticket.version || 0,
      resolution: "",
      note: "管理员内部核验记录",
    });
    ticket = await admin.api("/tickets/" + ticket.id, "PUT", {
      status: "RESOLVED",
      version: ticket.version,
      resolution: "测试问题已核验完成",
      note: "",
    });
    const messages = await client.api(
      "/conversations/" + conversation.id + "/messages",
    );
    assert.ok(
      messages.some(
        (m) => m.role === "human" && m.content.includes("测试问题已核验完成"),
      ),
    );
    const ownTickets = await client.api("/tickets");
    assert.ok(!JSON.stringify(ownTickets).includes("管理员内部核验记录"));
  },
);
await check("admin can view orders by customer", async () => {
  const customers = await admin.api("/admin/customers?q=bench-&page=1");
  assert.ok(customers.items.length);
  const me = await client.api("/auth/me");
  const result = await admin.api(
    "/admin/orders?userId=" + encodeURIComponent(me.user.id),
  );
  assert.ok(result.items.some((o) => o.id === order.id));
  assert.ok(result.items.every((o) => o.userId === me.user.id));
});
await check("logout revokes cookie immediately", async () => {
  const cookie = client.cookie;
  await client.api("/auth/logout", "POST");
  client.cookie = cookie;
  await assert.rejects(client.api("/auth/me"), (e) => e.status === 401);
});
console.log(
  "Report:",
  await save("business-smoke", {
    measuredAt: new Date().toISOString(),
    checks,
    limitations: [
      "本报告使用隔离测试客户和模拟余额，没有真实支付或真实物流",
      "并发样本是有限回归测试，不是容量压测",
    ],
  }),
);
