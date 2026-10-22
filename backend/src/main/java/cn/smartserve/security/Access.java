package cn.smartserve.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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

  public static final String COOKIE = "smartserve_session";
  private final AuthService auth;
  private final ObjectMapper mapper;

  @Value("${smartserve.signing-secret}")
  String signingSecret;

  @Value("${smartserve.allowed-origins}")
  String allowedOrigins;

  public Access(AuthService auth, ObjectMapper mapper) {
    this.auth = auth;
    this.mapper = mapper;
  }

  public String token(HttpServletRequest req) {
    if (req.getCookies() != null) for (Cookie cookie : req.getCookies())
      if (COOKIE.equals(cookie.getName())) return cookie.getValue();
    return null;
  }

  public String csrf(String token) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(
        new SecretKeySpec(
          signingSecret.getBytes(StandardCharsets.UTF_8),
          "HmacSHA256"
        )
      );
      return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(
          mac.doFinal(("csrf:" + token).getBytes(StandardCharsets.UTF_8))
        );
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static Actor actor(HttpServletRequest req) {
    return (Actor) req.getAttribute("actor");
  }

  public static void own(Actor actor, String owner) {
    if (
      actor == null || (!actor.admin() && !actor.userId().equals(owner))
    ) throw new ResponseStatusException(
      HttpStatus.FORBIDDEN,
      "无权访问其他用户的数据"
    );
  }

  public static void admin(Actor actor) {
    if (actor == null || !actor.admin()) throw new ResponseStatusException(
      HttpStatus.FORBIDDEN,
      "需要管理员权限"
    );
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest req) {
    return (
      !req.getRequestURI().startsWith("/api/") ||
      req.getRequestURI().equals("/api/mcp")
    );
  }

  @Override
  protected void doFilterInternal(
    HttpServletRequest req,
    HttpServletResponse res,
    FilterChain chain
  ) throws java.io.IOException, ServletException {
    try {
      boolean safe = Set.of("GET", "HEAD", "OPTIONS").contains(req.getMethod());
      String origin = req.getHeader("Origin");
      if (
        !safe &&
        origin != null &&
        !Set.of(allowedOrigins.split(",")).contains(origin)
      ) throw new ResponseStatusException(
        HttpStatus.FORBIDDEN,
        "请求来源不被允许"
      );
      String path = req.getRequestURI();
      boolean publicPath =
        path.equals("/api/status") ||
        path.equals("/api/auth/login") ||
        path.equals("/api/auth/register");
      if (!publicPath) {
        String token = token(req);
        req.setAttribute("actor", auth.authenticate(token));
        if (!safe) {
          String supplied = Objects.toString(req.getHeader("X-CSRF-Token"), "");
          if (
            !MessageDigest.isEqual(
              csrf(token).getBytes(StandardCharsets.UTF_8),
              supplied.getBytes(StandardCharsets.UTF_8)
            )
          ) throw new ResponseStatusException(
            HttpStatus.FORBIDDEN,
            "安全校验失效，请刷新页面后重试"
          );
        }
      }
      chain.doFilter(req, res);
    } catch (ResponseStatusException e) {
      res.setStatus(e.getStatusCode().value());
      res.setContentType("application/json;charset=UTF-8");
      mapper.writeValue(
        res.getOutputStream(),
        Map.of(
          "status",
          e.getStatusCode().value(),
          "message",
          Objects.toString(e.getReason(), "请求未完成")
        )
      );
    }
  }
}
