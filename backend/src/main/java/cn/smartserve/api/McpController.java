package cn.smartserve.api;

import cn.smartserve.agent.ToolRegistry;
import cn.smartserve.security.Access.Actor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Stateless JSON response mode of MCP Streamable HTTP. A private service credential carries trusted principal context. */
@RestController
public class McpController {

  private final ToolRegistry tools;

  @Value("${smartserve.mcp-secret}")
  String secret;

  public McpController(ToolRegistry tools) {
    this.tools = tools;
  }

  @PostMapping(value = "/api/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> rpc(
    @RequestHeader(value = "Origin", defaultValue = "") String origin,
    @RequestHeader(value = "Authorization", defaultValue = "") String auth,
    @RequestHeader(
      value = "X-SmartServe-Agent",
      defaultValue = "PRODUCT"
    ) String agent,
    @RequestHeader(
      value = "X-SmartServe-User",
      defaultValue = "customer-001"
    ) String user,
    @RequestHeader(
      value = "X-SmartServe-Admin",
      defaultValue = "false"
    ) String admin,
    @RequestBody Map<String, Object> request
  ) {
    if (!origin.isBlank()) {
      try {
        String host = java.net.URI.create(origin).getHost();
        if (
          !Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(host)
        ) return ResponseEntity.status(403).body(
          Map.of("message", "MCP Origin 被拒绝")
        );
      } catch (Exception e) {
        return ResponseEntity.status(403).build();
      }
    }
    if (
      !MessageDigest.isEqual(
        auth.getBytes(StandardCharsets.UTF_8),
        ("Bearer " + secret).getBytes(StandardCharsets.UTF_8)
      )
    ) return ResponseEntity.status(401).body(
      Map.of("message", "MCP服务凭证无效")
    );
    Object id = request.get("id");
    String method = Objects.toString(request.get("method"), "");
    if (
      id == null && method.startsWith("notifications/")
    ) return ResponseEntity.accepted().build();
    var response = new LinkedHashMap<String, Object>();
    response.put("jsonrpc", "2.0");
    response.put("id", id);
    try {
      if (
        !"2.0".equals(request.get("jsonrpc"))
      ) throw new IllegalArgumentException("需要 JSON-RPC 2.0");
      Object result = switch (method) {
        case "initialize" -> Map.of(
          "protocolVersion",
          "2025-03-26",
          "capabilities",
          Map.of("tools", Map.of("listChanged", false)),
          "serverInfo",
          Map.of("name", "smartserve-business-tools", "version", "2.0.0")
        );
        case "ping" -> Map.of();
        case "tools/list" -> Map.of("tools", tools.definitions(agent));
        case "tools/call" -> {
          var params = (Map<String, Object>) request.get("params");
          Object value = tools.call(
            agent,
            new Actor(user, Boolean.parseBoolean(admin)),
            (String) params.get("name"),
            (Map<String, Object>) params.getOrDefault("arguments", Map.of())
          );
          yield Map.of(
            "content",
            List.of(
              Map.of(
                "type",
                "text",
                "text",
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                  value
                )
              )
            ),
            "isError",
            false
          );
        }
        default -> throw new UnsupportedOperationException("未知方法");
      };
      response.put("result", result);
    } catch (Exception e) {
      if (method.equals("tools/call")) response.put(
        "result",
        Map.of(
          "content",
          List.of(
            Map.of(
              "type",
              "text",
              "text",
              e instanceof SecurityException
                ? "工具权限不足"
                : "工具执行失败：" + Objects.toString(e.getMessage(), "请重试")
            )
          ),
          "isError",
          true
        )
      );
      else response.put(
        "error",
        Map.of(
          "code",
          e instanceof UnsupportedOperationException ? -32601 : -32602,
          "message",
          e.getMessage()
        )
      );
    }
    return ResponseEntity.ok(response);
  }

  @GetMapping("/api/mcp")
  public ResponseEntity<Void> noServerNotifications() {
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
  }
}
