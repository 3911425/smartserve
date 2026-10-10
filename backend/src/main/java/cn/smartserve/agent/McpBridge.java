package cn.smartserve.agent;

import cn.smartserve.infra.JsonStore;
import cn.smartserve.security.Access.Actor;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class McpBridge {

  private final JsonStore db;
  private final HttpClient client = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(3))
    .build();
  private final Map<String, Set<String>> discovered = new ConcurrentHashMap<>();

  @Value("${server.port}")
  int port;

  @Value("${smartserve.mcp-secret}")
  String secret;

  public McpBridge(JsonStore db) {
    this.db = db;
  }

  private synchronized void initialize(String agent, Actor actor)
    throws Exception {
    if (discovered.containsKey(agent)) return;
    rpc(
      agent,
      actor,
      "initialize",
      Map.of(
        "protocolVersion",
        "2025-03-26",
        "capabilities",
        Map.of(),
        "clientInfo",
        Map.of("name", "SmartServe.DomainWorker", "version", "1.0.0")
      ),
      true
    );
    rpc(agent, actor, "notifications/initialized", Map.of(), false);
    var result = rpc(agent, actor, "tools/list", Map.of(), true);
    var definitions = (List<Map<String, Object>>) result.get("tools");
    var names = new HashSet<String>();
    definitions.forEach(d -> names.add(d.get("name").toString()));
    discovered.put(agent, Set.copyOf(names));
  }

  public Object call(
    String agent,
    Actor actor,
    String tool,
    Map<String, Object> args
  ) {
    try {
      initialize(agent, actor);
      if (!discovered.get(agent).contains(tool)) throw new SecurityException(
        "该工具没有在当前 Agent 的 MCP 目录中发布"
      );
      var result = rpc(
        agent,
        actor,
        "tools/call",
        Map.of("name", tool, "arguments", args),
        true
      );
      if (
        Boolean.TRUE.equals(result.get("isError"))
      ) throw new IllegalStateException("业务工具拒绝或失败");
      var content = (List<Map<String, Object>>) result.get("content");
      return db.mapper.readValue(
        content.get(0).get("text").toString(),
        Object.class
      );
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("工具调用已中断", e);
    } catch (Exception e) {
      throw new IllegalStateException("MCP工具执行未完成", e);
    }
  }

  private Map<String, Object> rpc(
    String agent,
    Actor actor,
    String method,
    Map<String, Object> params,
    boolean request
  ) throws Exception {
    var body = new LinkedHashMap<String, Object>();
    body.put("jsonrpc", "2.0");
    if (request) body.put("id", UUID.randomUUID().toString());
    body.put("method", method);
    body.put("params", params);
    var req = HttpRequest.newBuilder(
      URI.create("http://127.0.0.1:" + port + "/api/mcp")
    )
      .timeout(Duration.ofSeconds(20))
      .header("Content-Type", "application/json")
      .header("Accept", "application/json, text/event-stream")
      .header("Authorization", "Bearer " + secret)
      .header("X-SmartServe-Agent", agent)
      .header("X-SmartServe-User", actor.userId())
      .header("X-SmartServe-Admin", Boolean.toString(actor.admin()))
      .POST(HttpRequest.BodyPublishers.ofString(db.json(body)))
      .build();
    var response = client.send(req, HttpResponse.BodyHandlers.ofString());
    if (!request && response.statusCode() == 202) return Map.of();
    if (response.statusCode() != 200) throw new IllegalStateException(
      "MCP连接失败"
    );
    var payload = db.map(response.body());
    if (payload.get("result") == null) throw new IllegalStateException(
      "MCP协议响应不合法"
    );
    return (Map<String, Object>) payload.get("result");
  }
}
