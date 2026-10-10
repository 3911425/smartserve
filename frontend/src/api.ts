export type Entity = Record<string, any>;
export let token = sessionStorage.getItem("smartserve-token") || "";
export function setToken(value: string) {
  token = value;
  sessionStorage.setItem("smartserve-token", value);
}
export async function api<T = Entity>(
  path: string,
  method = "GET",
  body?: unknown,
): Promise<T> {
  const response = await fetch("/api" + path, {
    method,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: "Bearer " + token } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const result = await response
    .json()
    .catch(() => ({ message: "服务暂时不可用" }));
  if (!response.ok)
    throw new Error(result.message || `请求失败 (${response.status})`);
  return result;
}
export async function streamRun(
  id: string,
  onEvent: (event: Entity) => void,
  signal: AbortSignal,
) {
  let lastId = 0;
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      const response = await fetch(`/api/runs/${id}/stream`, {
        headers: {
          Authorization: "Bearer " + token,
          "Last-Event-ID": String(lastId),
        },
        signal,
      });
      if (!response.ok || !response.body) throw new Error("实时连接暂时不可用");
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";
      let completed = false;
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
            .filter((l) => l.startsWith("data:"))
            .map((l) => l.slice(5).trimStart())
            .join("\n");
          if (payload) {
            const event = JSON.parse(payload);
            lastId = Number(event.eventId) || lastId;
            onEvent(event);
            if (event.type === "completed" || event.type === "failed")
              completed = true;
          }
        }
      }
      if (completed) return;
      const run = await api(`/runs/${id}`);
      if (["COMPLETED", "FAILED"].includes(run.status)) return;
    } catch (error) {
      if (signal.aborted) throw error;
      if (attempt === 2) throw error;
    }
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
}
