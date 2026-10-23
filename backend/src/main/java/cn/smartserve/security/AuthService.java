package cn.smartserve.security;

import cn.smartserve.business.BusinessService;
import cn.smartserve.infra.JsonStore;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Passwords use BCrypt; browser session secrets are stored only as SHA-256 digests. */
@Service
public class AuthService {

  private final JsonStore db;
  private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(11);
  private final SecureRandom random = new SecureRandom();
  private final String dummyHash = passwords.encode(
    UUID.randomUUID().toString()
  );

  @Value("${smartserve.bootstrap-admin-user}")
  String adminUsername;

  @Value("${smartserve.bootstrap-admin-password}")
  String adminPassword;

  @Value("${smartserve.bootstrap-customer-password}")
  String customerPassword;

  @Value("${smartserve.session-hours}")
  int sessionHours;

  public AuthService(JsonStore db) {
    this.db = db;
  }

  public record Session(String token, Access.Actor actor) {}

  @Transactional
  public void bootstrap() {
    if (count("operator-001") == 0) {
      if (adminPassword.isBlank()) throw new IllegalStateException(
        "请先执行 scripts/init-env.ps1，生成本机管理员初始凭证"
      );
      insertAccount(
        "operator-001",
        normalize(adminUsername),
        adminPassword,
        "服务管理员",
        "ADMIN",
        true
      );
    }
    if (count("customer-001") == 0) insertAccount(
      "customer-001",
      "demo",
      customerPassword.isBlank() ? randomToken() : customerPassword,
      "体验客户",
      "CUSTOMER",
      true
    );
    for (String owner : db.jdbc.queryForList(
      "SELECT DISTINCT owner_id FROM customer_order",
      String.class
    ))
      if (count(owner) == 0) insertAccount(
        owner,
        "legacy-" + BusinessService.hash(owner).substring(0, 12),
        randomToken(),
        "历史客户",
        "CUSTOMER",
        false
      );
  }

  private int count(String id) {
    return db.jdbc.queryForObject(
      "SELECT COUNT(*) FROM user_account WHERE id=?",
      Integer.class,
      id
    );
  }

  @Transactional
  public Map<String, Object> register(
    String username,
    String password,
    String displayName
  ) {
    String name = normalize(username);
    checkPassword(password);
    if (
      name.equals(normalize(adminUsername)) ||
      name.equals("demo") ||
      name.startsWith("legacy-")
    ) throw error(HttpStatus.CONFLICT, "该账号名称不可注册");
    if (
      db.jdbc.queryForObject(
        "SELECT COUNT(*) FROM user_account WHERE username=?",
        Integer.class,
        name
      ) >
      0
    ) throw error(HttpStatus.CONFLICT, "账号已存在，请更换账号或直接登录");
    String id = JsonStore.id("user-");
    insertAccount(id, name, password, displayName.strip(), "CUSTOMER", true);
    return profile(new Access.Actor(id, false));
  }

  private void insertAccount(
    String id,
    String username,
    String password,
    String name,
    String role,
    boolean enabled
  ) {
    // Registration checks its policy before insertion. Bootstrap credentials are
    // supplied by the local operator and may predate the registration minimum.
    if (
      password == null ||
      password.isBlank() ||
      password.getBytes(StandardCharsets.UTF_8).length > 72
    ) throw error(HttpStatus.BAD_REQUEST, "初始密码不能为空，且UTF-8编码不超过72字节");
    db.jdbc.update(
      "INSERT INTO user_account(id,username,password_hash,display_name,role,enabled) VALUES(?,?,?,?,?,?)",
      id,
      username,
      passwords.encode(password),
      name,
      role,
      enabled
    );
    if (role.equals("CUSTOMER")) {
      db.jdbc.update(
        "INSERT INTO wallet(user_id,balance) VALUES(?,10000.00)",
        id
      );
      db.jdbc.update(
        "INSERT INTO wallet_ledger(id,user_id,business_key,kind,amount,balance_after) VALUES(?,?,?,'WELCOME',10000.00,10000.00)",
        JsonStore.id("ledger-"),
        id,
        "welcome:" + id
      );
    }
  }

  public Session login(String username, String password) {
    String name =
      username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
    var rows = db.jdbc.queryForList(
      "SELECT * FROM user_account WHERE username=?",
      name
    );
    var row = rows.isEmpty() ? null : rows.get(0);
    if (
      row != null &&
      row.get("locked_until") instanceof Timestamp t &&
      t.toInstant().isAfter(Instant.now())
    ) throw error(HttpStatus.TOO_MANY_REQUESTS, "登录失败次数过多，请稍后再试");
    boolean valid = passwords.matches(
      password == null ? "" : password,
      row == null ? dummyHash : row.get("password_hash").toString()
    );
    if (row == null || !valid || !Boolean.TRUE.equals(row.get("enabled"))) {
      if (row != null) {
        db.jdbc.update(
          "UPDATE user_account SET failed_logins=failed_logins+1 WHERE id=?",
          row.get("id")
        );
        int failures = db.jdbc.queryForObject(
          "SELECT failed_logins FROM user_account WHERE id=?",
          Integer.class,
          row.get("id")
        );
        if (failures >= 8) db.jdbc.update(
          "UPDATE user_account SET locked_until=?,failed_logins=0 WHERE id=?",
          Timestamp.from(Instant.now().plusSeconds(300)),
          row.get("id")
        );
      }
      throw error(HttpStatus.UNAUTHORIZED, "账号或密码不正确");
    }
    db.jdbc.update(
      "UPDATE user_account SET failed_logins=0,locked_until=NULL WHERE id=?",
      row.get("id")
    );
    String token = randomToken();
    db.jdbc.update(
      "INSERT INTO auth_session(token_hash,user_id,expires_at) VALUES(?,?,?)",
      BusinessService.hash(token),
      row.get("id"),
      Timestamp.from(Instant.now().plusSeconds(sessionHours * 3600L))
    );
    db.jdbc.update(
      "DELETE FROM auth_session WHERE expires_at<CURRENT_TIMESTAMP"
    );
    return new Session(
      token,
      new Access.Actor(
        row.get("id").toString(),
        "ADMIN".equals(row.get("role"))
      )
    );
  }

  public Access.Actor authenticate(String token) {
    if (
      token == null || token.length() < 30 || token.length() > 200
    ) throw error(HttpStatus.UNAUTHORIZED, "请先登录");
    var rows = db.jdbc.queryForList(
      "SELECT u.id,u.role FROM auth_session s JOIN user_account u ON u.id=s.user_id WHERE s.token_hash=? AND s.expires_at>CURRENT_TIMESTAMP AND u.enabled=TRUE",
      BusinessService.hash(token)
    );
    if (rows.isEmpty()) throw error(
      HttpStatus.UNAUTHORIZED,
      "登录已失效，请重新登录"
    );
    return new Access.Actor(
      rows.get(0).get("id").toString(),
      "ADMIN".equals(rows.get(0).get("role"))
    );
  }

  public void logout(String token) {
    if (token != null) db.jdbc.update(
      "DELETE FROM auth_session WHERE token_hash=?",
      BusinessService.hash(token)
    );
  }

  public Map<String, Object> profile(Access.Actor actor) {
    var rows = db.jdbc.queryForList(
      "SELECT id,username,display_name,role,phone,shipping_address,created_at FROM user_account WHERE id=?",
      actor.userId()
    );
    if (rows.isEmpty()) throw error(HttpStatus.NOT_FOUND, "账号不存在");
    var r = rows.get(0);
    return Map.of(
      "id",
      r.get("id"),
      "username",
      r.get("username"),
      "displayName",
      r.get("display_name"),
      "role",
      r.get("role"),
      "phone",
      r.get("phone"),
      "shippingAddress",
      r.get("shipping_address"),
      "createdAt",
      r.get("created_at").toString()
    );
  }

  @Transactional
  public Map<String, Object> updateProfile(
    Access.Actor actor,
    String name,
    String phone,
    String address
  ) {
    if (
      name.isBlank() ||
      name.length() > 60 ||
      phone.length() > 30 ||
      address.length() > 300
    ) throw error(HttpStatus.BAD_REQUEST, "个人资料长度不正确");
    db.jdbc.update(
      "UPDATE user_account SET display_name=?,phone=?,shipping_address=? WHERE id=?",
      name.strip(),
      phone.strip(),
      address.strip(),
      actor.userId()
    );
    return profile(actor);
  }

  @Transactional
  public void changePassword(
    Access.Actor actor,
    String oldPassword,
    String nextPassword
  ) {
    checkPassword(nextPassword);
    String hash = db.jdbc.queryForObject(
      "SELECT password_hash FROM user_account WHERE id=? FOR UPDATE",
      String.class,
      actor.userId()
    );
    if (!passwords.matches(oldPassword, hash)) throw error(
      HttpStatus.BAD_REQUEST,
      "当前密码不正确"
    );
    db.jdbc.update(
      "UPDATE user_account SET password_hash=? WHERE id=?",
      passwords.encode(nextPassword),
      actor.userId()
    );
    db.jdbc.update("DELETE FROM auth_session WHERE user_id=?", actor.userId());
  }

  @Transactional
  public void resetPassword(Access.Actor admin, String id, String password) {
    Access.admin(admin);
    checkPassword(password);
    if (
      db.jdbc.update(
        "UPDATE user_account SET password_hash=?,failed_logins=0,locked_until=NULL WHERE id=? AND role='CUSTOMER'",
        passwords.encode(password),
        id
      ) !=
      1
    ) throw error(HttpStatus.NOT_FOUND, "客户账号不存在");
    db.jdbc.update("DELETE FROM auth_session WHERE user_id=?", id);
  }

  public Map<String, Object> customers(
    Access.Actor admin,
    String query,
    int page
  ) {
    Access.admin(admin);
    int offset = Math.max(0, page - 1) * 20;
    String needle =
      "%" +
      query.strip().toLowerCase(Locale.ROOT).replace("%", "").replace("_", "") +
      "%";
    var rows = db.jdbc.queryForList(
      "SELECT u.id,u.username,u.display_name,u.enabled,u.created_at,(SELECT COUNT(*) FROM shop_order o WHERE o.user_id=u.id) AS order_count FROM user_account u WHERE u.role='CUSTOMER' AND (LOWER(u.username) LIKE ? OR LOWER(u.display_name) LIKE ?) ORDER BY u.created_at DESC,u.id LIMIT 20 OFFSET ?",
      needle,
      needle,
      offset
    );
    return Map.of("items", rows, "page", Math.max(1, page), "pageSize", 20);
  }

  public String randomToken() {
    byte[] b = new byte[32];
    random.nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  private String normalize(String s) {
    if (
      s == null || !s.strip().matches("[A-Za-z0-9][A-Za-z0-9_-]{2,39}")
    ) throw error(
      HttpStatus.BAD_REQUEST,
      "账号需为3～40位字母、数字、下划线或短横线"
    );
    return s.strip().toLowerCase(Locale.ROOT);
  }

  private void checkPassword(String s) {
    if (
      s == null ||
      s.length() < 8 ||
      s.getBytes(StandardCharsets.UTF_8).length > 72
    ) throw error(
      HttpStatus.BAD_REQUEST,
      "密码至少8个字符，且UTF-8编码不超过72字节"
    );
  }

  private ResponseStatusException error(HttpStatus s, String message) {
    return new ResponseStatusException(s, message);
  }
}
