package cn.smartserve.api;

import cn.smartserve.business.CommerceService;
import cn.smartserve.security.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class CommerceController {

  private final CommerceService commerce;
  private final AuthService auth;

  public CommerceController(CommerceService commerce, AuthService auth) {
    this.commerce = commerce;
    this.auth = auth;
  }

  public record Purchase(
    @NotBlank @Size(max = 100) String productId,
    @Min(1) @Max(20) int quantity,
    @NotBlank @Size(max = 60) String recipient,
    @NotBlank @Size(max = 30) String phone,
    @NotBlank @Size(max = 300) String address,
    @NotBlank @Size(max = 100) String requestKey
  ) {}

  public record Transition(
    @NotBlank @Pattern(regexp = "SHIPPED|DELIVERED") String status,
    @Min(0) int version
  ) {}

  public record Review(
    boolean approve,
    boolean returnedToStock,
    @NotBlank @Size(max = 500) String note,
    @Min(0) int version
  ) {}

  public record Reset(@NotBlank @Size(min = 8, max = 72) String password) {}
  public record Version(@NotNull @Min(0) Integer version) {}

  @PostMapping("/refunds/{id}/withdraw")
  Object withdraw(@PathVariable String id, @Valid @RequestBody Version in, HttpServletRequest req) {
    return commerce.withdrawRefund(Access.actor(req), id, in.version());
  }

  @GetMapping("/wallet")
  Object wallet(HttpServletRequest req) {
    return commerce.wallet(Access.actor(req));
  }

  @GetMapping("/orders/page")
  Object page(
    @RequestParam(defaultValue = "") String status,
    @RequestParam(defaultValue = "1") @Min(1) @Max(10000) int page,
    HttpServletRequest req
  ) {
    return commerce.pageOrders(Access.actor(req), "", status, page);
  }

  @PostMapping("/purchases")
  Object purchase(@Valid @RequestBody Purchase in, HttpServletRequest req) {
    return commerce.purchase(
      Access.actor(req),
      new CommerceService.Purchase(
        in.productId(),
        in.quantity(),
        in.recipient(),
        in.phone(),
        in.address(),
        in.requestKey()
      )
    );
  }

  @PostMapping("/orders/{id}/cancel")
  Object cancel(@PathVariable String id, HttpServletRequest req) {
    return commerce.cancel(Access.actor(req), id);
  }

  @GetMapping("/refunds")
  Object refunds(HttpServletRequest req) {
    return commerce.refunds(Access.actor(req));
  }

  @GetMapping("/admin/customers")
  Object customers(
    @RequestParam(defaultValue = "") @Size(max = 60) String q,
    @RequestParam(defaultValue = "1") @Min(1) @Max(10000) int page,
    HttpServletRequest req
  ) {
    return auth.customers(Access.actor(req), q, page);
  }

  @GetMapping("/admin/orders")
  Object orders(
    @RequestParam(defaultValue = "") String userId,
    @RequestParam(defaultValue = "") String status,
    @RequestParam(defaultValue = "1") @Min(1) @Max(10000) int page,
    HttpServletRequest req
  ) {
    Access.admin(Access.actor(req));
    return commerce.pageOrders(Access.actor(req), userId, status, page);
  }

  @PostMapping("/admin/orders/{id}/advance")
  Object advance(
    @PathVariable String id,
    @Valid @RequestBody Transition in,
    HttpServletRequest req
  ) {
    return commerce.advance(Access.actor(req), id, in.status(), in.version());
  }

  @PostMapping("/admin/refunds/{id}/review")
  Object review(
    @PathVariable String id,
    @Valid @RequestBody Review in,
    HttpServletRequest req
  ) {
    return commerce.reviewRefund(
      Access.actor(req),
      id,
      in.approve(),
      in.returnedToStock(),
      in.note(),
      in.version()
    );
  }

  @PostMapping("/admin/customers/{id}/reset-password")
  Object reset(
    @PathVariable String id,
    @Valid @RequestBody Reset in,
    HttpServletRequest req
  ) {
    auth.resetPassword(Access.actor(req), id, in.password());
    return Map.of("ok", true);
  }
}
