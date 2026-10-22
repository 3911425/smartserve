package cn.smartserve.business;

import cn.smartserve.security.AuthService;
import org.springframework.boot.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Transactional bootstrap methods are invoked through Spring proxies, not self-invocation. */
@Component
@Order(20)
public class BootstrapService implements ApplicationRunner {

  private final AuthService auth;
  private final CommerceService commerce;

  public BootstrapService(AuthService auth, CommerceService commerce) {
    this.auth = auth;
    this.commerce = commerce;
  }

  @Override
  public void run(ApplicationArguments args) {
    auth.bootstrap();
    commerce.migrateLegacyOrders();
  }
}
