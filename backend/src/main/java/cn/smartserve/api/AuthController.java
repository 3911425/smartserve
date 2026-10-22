package cn.smartserve.api;

import cn.smartserve.security.*;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

  private final AuthService auth;
  private final Access access;

  @Value("${smartserve.secure-cookie}")
  boolean secure;

  @Value("${smartserve.session-hours}")
  int hours;

  public AuthController(AuthService auth, Access access) {
    this.auth = auth;
    this.access = access;
  }

  public record Login(
    @NotBlank @Size(max = 40) String username,
    @NotBlank @Size(max = 72) String password
  ) {}

  public record Registration(
    @NotBlank @Size(max = 40) String username,
    @NotBlank @Size(min = 8, max = 72) String password,
    @NotBlank @Size(max = 60) String displayName
  ) {}

  public record Profile(
    @NotBlank @Size(max = 60) String displayName,
    @NotNull @Size(max = 30) String phone,
    @NotNull @Size(max = 300) String shippingAddress
  ) {}

  public record Password(
    @NotBlank String currentPassword,
    @NotBlank @Size(min = 8, max = 72) String newPassword
  ) {}

  @PostMapping("/register")
  Object register(@Valid @RequestBody Registration in) {
    return auth.register(in.username(), in.password(), in.displayName());
  }

  @PostMapping("/login")
  Object login(@Valid @RequestBody Login in, HttpServletResponse response) {
    var s = auth.login(in.username(), in.password());
    cookie(response, s.token(), Duration.ofHours(hours));
    return Map.of(
      "user",
      auth.profile(s.actor()),
      "csrfToken",
      access.csrf(s.token())
    );
  }

  @GetMapping("/me")
  Object me(HttpServletRequest req) {
    return Map.of(
      "user",
      auth.profile(Access.actor(req)),
      "csrfToken",
      access.csrf(access.token(req))
    );
  }

  @PostMapping("/logout")
  Object logout(HttpServletRequest req, HttpServletResponse response) {
    auth.logout(access.token(req));
    cookie(response, "", Duration.ZERO);
    return Map.of("ok", true);
  }

  @PutMapping("/profile")
  Object profile(@Valid @RequestBody Profile in, HttpServletRequest req) {
    return auth.updateProfile(
      Access.actor(req),
      in.displayName(),
      in.phone(),
      in.shippingAddress()
    );
  }

  @PutMapping("/password")
  Object password(
    @Valid @RequestBody Password in,
    HttpServletRequest req,
    HttpServletResponse response
  ) {
    auth.changePassword(
      Access.actor(req),
      in.currentPassword(),
      in.newPassword()
    );
    cookie(response, "", Duration.ZERO);
    return Map.of("ok", true);
  }

  private void cookie(
    HttpServletResponse response,
    String value,
    Duration age
  ) {
    response.addHeader(
      HttpHeaders.SET_COOKIE,
      ResponseCookie.from(Access.COOKIE, value)
        .httpOnly(true)
        .secure(secure)
        .sameSite("Lax")
        .path("/api")
        .maxAge(age)
        .build()
        .toString()
    );
  }
}
