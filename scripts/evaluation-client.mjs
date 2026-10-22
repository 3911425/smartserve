import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
export const root = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
export const base = process.env.SMARTSERVE_BASE_URL || "http://127.0.0.1:18082";
export async function environment() {
  const text = await fs.readFile(path.join(root, ".env"), "utf8");
  return Object.fromEntries(
    text
      .split(/\r?\n/)
      .filter((s) => s && !s.startsWith("#") && s.includes("="))
      .map((s) => {
        const i = s.indexOf("=");
        return [
          s.slice(0, i),
          s
            .slice(i + 1)
            .trim()
            .replace(/^"|"$/g, ""),
        ];
      }),
  );
}
export class Client {
  cookie = "";
  csrf = "";
  async api(route, method = "GET", body) {
    const response = await fetch(base + "/api" + route, {
      method,
      headers: {
        "Content-Type": "application/json",
        ...(this.cookie ? { Cookie: this.cookie } : {}),
        ...(this.csrf ? { "X-CSRF-Token": this.csrf } : {}),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(180000),
    });
    const value = await response.json();
    if (!response.ok)
      throw Object.assign(
        new Error(value.message || `HTTP ${response.status}`),
        { status: response.status },
      );
    const cookie = response.headers.get("set-cookie");
    if (cookie) this.cookie = cookie.split(";")[0];
    if (value.csrfToken) this.csrf = value.csrfToken;
    return value;
  }
  async fixture() {
    const deadline = Date.now() + 180000;
    let ready = false;
    while (Date.now() < deadline) {
      try {
        const status = await this.api("/status");
        if (status.knowledge?.chunks > 0 && status.knowledge?.revision) {
          ready = true;
          break;
        }
      } catch {}
      await new Promise((resolve) => setTimeout(resolve, 800));
    }
    if (!ready)
      throw new Error(
        "Evaluation API/knowledge index did not become ready within 180 seconds",
      );
    const username = "bench-" + crypto.randomUUID().slice(0, 12),
      password = crypto.randomUUID() + "Aa1";
    await this.api("/auth/register", "POST", {
      username,
      password,
      displayName: "评测专用客户",
    });
    await this.api("/auth/login", "POST", { username, password });
    return this;
  }
}
export async function save(name, report) {
  const folder = path.join(root, "data/evaluation/results");
  await fs.mkdir(folder, { recursive: true });
  const file = path.join(folder, name + ".json");
  await fs.writeFile(file, JSON.stringify(report, null, 2) + "\n");
  return file;
}
export function option(name, fallback) {
  const index = process.argv.indexOf("--" + name);
  return index < 0 ? fallback : process.argv[index + 1];
}
