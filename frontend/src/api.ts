import type { Entity } from "./format";
export type { Entity } from "./format";
export const BUSINESS_RULES_VERSION = "2.1-20261023";
let csrf = "";
export function setCsrf(value: string) {
  csrf = value;
}
export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
  ) {
    super(message);
  }
}
export async function api<T = Entity>(
  path: string,
  method = "GET",
  body?: unknown,
): Promise<T> {
  const response = await fetch("/api" + path, {
    method,
    credentials: "same-origin",
    headers: {
      "Content-Type": "application/json",
      ...(csrf ? { "X-CSRF-Token": csrf } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const result = await response
    .json()
    .catch(() => ({ message: "服务暂时不可用" }));
  if (!response.ok) {
    if (
      response.status === 401 &&
      !path.startsWith("/auth/login") &&
      path !== "/auth/me"
    )
      window.dispatchEvent(new Event("smartserve:unauthorized"));
    throw new ApiError(
      result.message || `请求失败 (${response.status})`,
      response.status,
    );
  }
  return result;
}
export async function streamRun(
  id: string,
  onEvent: (event: Entity) => void,
  signal: AbortSignal,
) {
  let lastId = 0;
  for (let attempt = 0; attempt < 4; attempt++) {
    try {
      const response = await fetch(`/api/runs/${id}/stream`, {
        credentials: "same-origin",
        headers: { "Last-Event-ID": String(lastId) },
        signal,
      });
      if (!response.ok || !response.body)
        throw new ApiError("实时连接暂时不可用", response.status);
      const reader = response.body.getReader(),
        decoder = new TextDecoder();
      let buffer = "",
        finished = false;
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder
          .decode(value, { stream: true })
          .replace(/\r\n/g, "\n");
        let split: number;
        while ((split = buffer.indexOf("\n\n")) >= 0) {
          const block = buffer.slice(0, split);
          buffer = buffer.slice(split + 2);
          const payload = block
            .split("\n")
            .filter((line) => line.startsWith("data:"))
            .map((line) => line.slice(5).trimStart())
            .join("\n");
          if (payload) {
            const event = JSON.parse(payload);
            lastId = Number(event.eventId) || lastId;
            onEvent(event);
            if (["completed", "failed", "cancelled"].includes(event.type))
              finished = true;
          }
        }
      }
      if (finished) return;
      const run = await api(`/runs/${id}`);
      if (["COMPLETED", "FAILED", "CANCELLED"].includes(run.status)) return;
    } catch (e) {
      if (signal.aborted) throw e;
      if (attempt === 3) throw e;
    }
    await new Promise((resolve) =>
      setTimeout(resolve, Math.min(2000, 500 * 2 ** attempt)),
    );
  }
  throw new Error("实时连接重试后仍未结束，请刷新会话查看持久结果");
}
