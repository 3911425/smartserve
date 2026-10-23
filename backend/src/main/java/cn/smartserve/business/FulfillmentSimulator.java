package cn.smartserve.business;

import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/** Select once per committed purchase; replays return the persisted order. */
@Component
public class FulfillmentSimulator {
  public String initialStatus() {
    return switch (ThreadLocalRandom.current().nextInt(3)) {
      case 0 -> "PAID";
      case 1 -> "SHIPPED";
      default -> "DELIVERED";
    };
  }
}
