import fs from "node:fs/promises";
import path from "node:path";
import { Client, root, save, option } from "./evaluation-client.mjs";
const client = await new Client().fixture();
const dataset = JSON.parse(
  await fs.readFile(
    path.join(root, "data/evaluation/retrieval-cases.json"),
    "utf8",
  ),
);
const split = option("split", "all");
const cases = dataset.cases.filter((c) => split === "all" || c.split === split);
if (!cases.length) throw new Error("没有匹配的评测用例");
const metrics = [],
  records = [];
for (const strategy of ["vector", "bm25", "hybrid"]) {
  let recall = 0,
    mrr = 0,
    coverage = 0,
    positive = 0,
    falseAccepted = 0,
    negative = 0;
  for (const item of cases) {
    const results = await client.api(
      "/knowledge/search?" +
        new URLSearchParams({ query: item.question, strategy }),
    );
    const headings = [...new Set(results.map((r) => r.heading))];
    if (item.negative) {
      negative++;
      if (results.some((r) => r.accepted)) falseAccepted++;
    } else {
      positive++;
      recall +=
        item.relevant.filter((h) => headings.includes(h)).length /
        item.relevant.length;
      const first = results.findIndex((r) => item.relevant.includes(r.heading));
      mrr += first < 0 ? 0 : 1 / (first + 1);
      if (results.some((r) => r.accepted && item.relevant.includes(r.heading)))
        coverage++;
    }
    records.push({
      id: item.id,
      split: item.split,
      question: item.question,
      strategy,
      relevant: item.relevant,
      results: results.map((r) => ({
        id: r.id,
        heading: r.heading,
        source: r.source,
        score: r.score,
        semanticScore: r.semanticScore,
        lexicalScore: r.lexicalScore,
        accepted: r.accepted,
        content: r.content,
      })),
    });
  }
  metrics.push({
    strategy,
    positive,
    recallAt5: positive ? recall / positive : null,
    mrrAt5: positive ? mrr / positive : null,
    acceptedRelevantCoverage: positive ? coverage / positive : null,
    negative,
    falseAccepted,
    falseAcceptRate: negative ? falseAccepted / negative : null,
  });
  console.log(strategy, JSON.stringify(metrics.at(-1)));
}
const status = await client.api("/status");
const file = await save("retrieval-" + split, {
  measuredAt: new Date().toISOString(),
  appVersion: status.version,
  knowledge: status.knowledge,
  datasetVersion: dataset.version,
  split,
  metrics,
  records,
  limitations: dataset.limitations,
});
console.log("Report:", file);
if (process.argv.includes("--gate")) {
  const hybrid = metrics.find((m) => m.strategy === "hybrid");
  if (!hybrid || hybrid.recallAt5 < 0.9 || hybrid.falseAccepted > 0) {
    console.error(
      "Retrieval gate failed: hybrid Recall@5 >= 0.90 and zero accepted negative cases required for this fixed regression set",
    );
    process.exitCode = 1;
  }
}
