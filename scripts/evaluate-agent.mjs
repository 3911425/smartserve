import fs from "node:fs/promises";
import path from "node:path";
import { Client, root, save, option } from "./evaluation-client.mjs";
const client = await new Client().fixture();
const status = await client.api("/status");
const paid = process.argv.includes("--paid");
if (status.modelEnabled && !paid)
  throw new Error(
    "当前服务器启用了付费模型；明确传 --paid 才运行，默认最多3例/30次模型尝试。",
  );
const dataset = JSON.parse(
  await fs.readFile(
    path.join(root, "data/evaluation/agent-cases.json"),
    "utf8",
  ),
);
const limit = Math.min(
  Number(option("limit", status.modelEnabled ? "3" : "100")),
  status.modelEnabled ? 10 : 100,
);
const budget = Math.min(Number(option("max-calls", "30")), 100);
const order = await client.api("/purchases", "POST", {
  productId: "plug-p1",
  quantity: 1,
  recipient: "评测收件人",
  phone: "00000000000",
  address: "评测专用模拟地址",
  requestKey: crypto.randomUUID(),
});
const other = await new Client().fixture();
const foreign = await other.api("/purchases", "POST", {
  productId: "bulb-l1",
  quantity: 1,
  recipient: "权限测试",
  phone: "00000000000",
  address: "模拟地址",
  requestKey: crypto.randomUUID(),
});
const orderStatusLabel = { PAID: "待发货", SHIPPED: "运输中", DELIVERED: "已送达" }[order.status];
const rows = [];
let calls = 0;
for (const c of dataset.cases.slice(0, limit)) {
  if (status.modelEnabled && calls + 10 > budget) {
    console.log("已达到保守调用预算，停止新增任务");
    break;
  }
  const before = await client.api("/wallet"),
    refundBefore = await client.api("/refunds");
  const conv = await client.api("/conversations", "POST");
  let final,
    events = [],
    turnResults = [],
    taskTokens = 0,
    taskCalls = 0;
  const begin = performance.now();
  for (const text of c.turns) {
    const q = text
      .replaceAll("{order}", order.id)
      .replaceAll("{foreign}", foreign.id);
    const run = await client.api("/runs", "POST", {
      conversationId: conv.id,
      question: q,
      requestKey: crypto.randomUUID(),
    });
    const until = Date.now() + 180000;
    do {
      final = await client.api("/runs/" + run.id);
      if (["COMPLETED", "FAILED", "CANCELLED"].includes(final.status)) break;
      await new Promise((r) => setTimeout(r, 200));
    } while (Date.now() < until);
    events.push(...(await client.api("/runs/" + run.id + "/events")));
    calls += Number(final.modelCalls || 0);
    taskCalls += Number(final.modelCalls || 0);
    taskTokens += Number(final.tokens || 0);
    turnResults.push({
      question: q,
      answer: final.answer,
      runId: final.id,
      routes: final.routes,
      modelCalls: final.modelCalls,
      knownTokens: final.tokens,
    });
    if (status.modelEnabled && calls >= budget) break;
  }
  const after = await client.api("/wallet"),
    refundAfter = await client.api("/refunds");
  const routeOk = c.routes.every((r) => final.routes?.includes(r));
  const answerOk =
    !c.contains ||
    c.contains.every((word) =>
      String(final.answer).toLowerCase().includes(word.replaceAll("{orderStatus}", orderStatusLabel).toLowerCase()),
    );
  const financialBoundary =
    Number(before.balance) === Number(after.balance) &&
    refundBefore.length === refundAfter.length;
  const tools = events.filter((e) => e.type === "tool").map((e) => e.tool);
  const toolOk = !c.tools || c.tools.every((t) => tools.includes(t));
  const result = {
    id: c.id,
    category: c.category,
    question: c.turns
      .join("\n")
      .replaceAll("{order}", order.id)
      .replaceAll("{foreign}", foreign.id),
    reference: c.reference,
    status: final.status,
    validation: final.validation,
    fallbackEvents: events.filter((e) => e.type === "fallback"),
    routes: final.routes,
    tools,
    answer: final.answer,
    sources: final.sources || [],
    verifiedFacts: final.verifiedFacts || [],
    runId: final.id,
    modelCalls: taskCalls,
    knownTokens: taskTokens,
    turns: turnResults,
    durationMs: Math.round(performance.now() - begin),
    checks: {
      completed: final.status === "COMPLETED",
      routeOk,
      answerOk,
      toolOk,
      financialBoundary,
    },
    calls: await client.api("/runs/" + final.id + "/model-calls"),
  };
  result.passed = Object.values(result.checks).every(Boolean);
  rows.push(result);
  console.log(
    c.id,
    result.passed ? "PASS" : "FAIL",
    JSON.stringify(result.checks),
  );
}
if (order.status === "PAID") await client.api("/orders/" + order.id + "/cancel", "POST");
if (foreign.status === "PAID") await other.api("/orders/" + foreign.id + "/cancel", "POST");
const file = await save(
  status.modelEnabled ? "agent-online" : "agent-offline",
  {
    measuredAt: new Date().toISOString(),
    appVersion: status.version,
    modelEnabled: status.modelEnabled,
    knowledge: status.knowledge,
    datasetVersion: dataset.version,
    fixtureOrderStatus: order.status,
    completed: rows.length,
    passed: rows.filter((r) => r.passed).length,
    modelCalls: calls,
    knownTokens: rows.reduce((n, r) => n + Number(r.knownTokens || 0), 0),
    rows,
    limitations: [
      "固定模拟业务夹具，不代表生产成功率",
      "关键词只验证关键事实，不能衡量完整语义正确性",
      "离线执行不评估模型决策质量；真实模型结果单独保存",
    ],
  },
);
console.log("Report:", file);
if (rows.some((r) => !r.passed)) process.exitCode = 1;
