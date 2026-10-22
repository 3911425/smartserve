import fs from "node:fs/promises";
import path from "node:path";
import { Client, environment, root, save } from "./evaluation-client.mjs";
const env = await environment(),
  client = new Client();
await client.api("/auth/login", "POST", {
  username: env.BOOTSTRAP_ADMIN_USER || "admin",
  password: env.BOOTSTRAP_ADMIN_PASSWORD,
});
const report = JSON.parse(
  await fs.readFile(
    path.join(root, "data/evaluation/results/agent-online.json"),
    "utf8",
  ),
);
const rows = [];
for (const item of report.rows) {
  const run = await client.api("/runs/" + item.runId),
    events = await client.api("/runs/" + item.runId + "/events");
  item.verifiedFacts = run.verifiedFacts || [];
  const exportEvent = events.filter((e) => e.type === "trace_export").at(-1);
  let remoteStatus = null,
    rootFound = false;
  if (env.LANGSMITH_API_KEY) {
    try {
      const r = await fetch(
        (env.LANGSMITH_ENDPOINT || "https://api.smith.langchain.com").replace(
          /\/$/,
          "",
        ) +
          "/runs/" +
          run.traceId,
        {
          headers: { "x-api-key": env.LANGSMITH_API_KEY },
          signal: AbortSignal.timeout(15000),
        },
      );
      remoteStatus = r.status;
      if (r.ok) {
        const remote = await r.json();
        rootFound = remote.id === run.traceId && remote.run_type === "chain";
      }
    } catch {
      remoteStatus = "network-error";
    }
  }
  rows.push({
    caseId: item.id,
    runId: item.runId,
    traceId: run.traceId,
    exportStatus: exportEvent?.status || "pending",
    exportLabel: exportEvent?.label || "",
    remoteStatus,
    rootFound,
  });
}
await client.api("/auth/logout", "POST");
report.evidenceHydratedAt = new Date().toISOString();
await fs.writeFile(
  path.join(root, "data/evaluation/results/agent-online.json"),
  JSON.stringify(report, null, 2) + "\n",
);
console.log(JSON.stringify(rows, null, 2));
console.log(
  "Report:",
  await save("remote-traces", {
    measuredAt: new Date().toISOString(),
    rows,
    limitations: [
      "Only synthetic benchmark questions were traced",
      "API acceptance and root read-back do not verify every remote UI field or billing computation",
    ],
  }),
);
