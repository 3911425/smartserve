package cn.smartserve.security;

import cn.smartserve.infra.JsonStore;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

@Component
public class Access extends OncePerRequestFilter {

  public record Actor(String userId, boolean admin) {}

  private final JsonStore store;

  @Value("${smartserve.demo-access}")
  public boolean demo;

  @Value("${smartserve.signing-secret}")
  String secret;

  @Value("${smartserve.customer-token}")
  String customerToken;

  @Value("${smartserve.admin-token}")
  String adminToken;

  public Access(JsonStore store) {
    this.store = store;
  }

  private String sign(String text) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(
        new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256")
      );
      return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public Map<String, Object> issue(String role) {
    if (!demo) throw new ResponseStatusException(
      HttpStatus.FORBIDDEN,
      "演示角色切换已关闭"
    );
    boolean admin = "admin".equals(role);
    String body = Base64.getUrlEncoder()
      .withoutPadding()
      .encodeToString(
        store
          .json(
            Map.of(
              "uid",
              admin ? "operator-001" : "customer-001",
              "admin",
              admin,
              "exp",
              Instant.now().plusSeconds(43200).getEpochSecond()
            )
          )
          .getBytes(StandardCharsets.UTF_8)
      );
    return Map.of(
      "token",
      body + "." + sign(body),
      "userId",
      admin ? "operator-001" : "customer-001",
      "role",
      admin ? "admin" : "customer",
      "demo",
      true
    );
  }

  public Actor parse(String token) {
    if (!demo) {
      if (!adminToken.isBlank() && equal(token, adminToken)) return new Actor(
        "operator-001",
        true
      );
      if (
        !customerToken.isBlank() && equal(token, customerToken)
      ) return new Actor("customer-001", false);
      throw new ResponseStatusException(
        HttpStatus.UNAUTHORIZED,
        "需要有效访问凭证"
      );
    }
    try {
      String[] parts = token.split("\\.");
      if (
        parts.length != 2 || !equal(sign(parts[0]), parts[1])
      ) throw new IllegalArgumentException();
      var m = store.map(
        new String(
          Base64.getUrlDecoder().decode(parts[0]),
          StandardCharsets.UTF_8
        )
      );
      if (
        ((Number) m.get("exp")).longValue() < Instant.now().getEpochSecond()
      ) throw new IllegalArgumentException();
      return new Actor(
        (String) m.get("uid"),
        Boolean.TRUE.equals(m.get("admin"))
      );
    } catch (Exception e) {
      throw new ResponseStatusException(
        HttpStatus.UNAUTHORIZED,
        "会话已过期，请重新选择角色"
      );
    }
  }

  private boolean equal(String a, String b) {
    return MessageDigest.isEqual(
      a.getBytes(StandardCharsets.UTF_8),
      b.getBytes(StandardCharsets.UTF_8)
    );
  }

  public static Actor actor(HttpServletRequest request) {
    return (Actor) request.getAttribute("actor");
  }

  public static void own(Actor actor, String owner) {
    if (
      !actor.admin() && !actor.userId().equals(owner)
    ) throw new ResponseStatusException(
      HttpStatus.FORBIDDEN,
      "无权访问其他用户的数据"
    );
  }

  public static void admin(Actor actor) {
    if (!actor.admin()) throw new ResponseStatusException(
      HttpStatus.FORBIDDEN,
      "需要管理员权限"
    );
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest r) {
    return (
      !r.getRequestURI().startsWith("/api/") ||
      r.getRequestURI().equals("/api/demo/session") ||
      r.getRequestURI().equals("/api/status") ||
      r.getRequestURI().equals("/api/mcp")
    );
  }

  @Override
  protected void doFilterInternal(
    HttpServletRequest req,
    HttpServletResponse res,
    FilterChain chain
  ) throws java.io.IOException, ServletException {
    try {
      String h = req.getHeader("Authorization");
      if (
        h == null || !h.startsWith("Bearer ")
      ) throw new ResponseStatusException(
        HttpStatus.UNAUTHORIZED,
        "缺少会话凭证"
      );
      req.setAttribute("actor", parse(h.substring(7)));
      chain.doFilter(req, res);
    } catch (ResponseStatusException e) {
      res.setStatus(e.getStatusCode().value());
      res.setContentType("application/json;charset=UTF-8");
      res
        .getWriter()
        .write(
          store.json(
            Map.of(
              "message",
              e.getReason(),
              "status",
              e.getStatusCode().value()
            )
          )
        );
    }
  }
}
