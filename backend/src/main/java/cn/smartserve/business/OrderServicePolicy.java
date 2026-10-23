package cn.smartserve.business;

import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** One rule set for order screens, HTTP commands and Agent tools. */
public final class OrderServicePolicy {
  private OrderServicePolicy() {}

  public static Map<String, Object> evaluate(String status, Timestamp deliveredAt, String refundStatus) {
    Long days = deliveredAt == null ? null : ChronoUnit.DAYS.between(
      deliveredAt.toInstant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate(),
      LocalDate.now(ZoneId.of("Asia/Shanghai"))
    );
    boolean inWindow = status.equals("DELIVERED") && days != null && days >= 0 && days <= 7;
    boolean draft = refundStatus.equals("AWAITING_CONFIRMATION");
    boolean pending = refundStatus.equals("PENDING_REVIEW");
    boolean canApply = inWindow && !draft && !pending && !refundStatus.equals("REFUNDED");
    String code;
    String message;
    if (status.equals("CANCELLED")) {
      code = "CANCELLED"; message = "订单已取消，不再发货，也不能重复退款。";
    } else if (status.equals("REFUNDED")) {
      code = "REFUNDED"; message = "售后退款已完成，不能重复退款；其他问题可联系人工。";
    } else if (pending) {
      code = "PENDING_REVIEW"; message = "售后申请正在审核，可查看进度或在审核前撤回。";
    } else if (draft && inWindow) {
      code = "AWAITING_CONFIRMATION"; message = "已有待确认的售后申请，请继续确认或撤回；尚未退款。";
    } else if (status.equals("PAID")) {
      code = "CANCELLABLE"; message = "尚未发货，可取消订单；钱包支付退回模拟余额，库存同时恢复。";
    } else if (status.equals("SHIPPED")) {
      code = "IN_TRANSIT"; message = "订单运输中，不能直接取消或按已签收订单申请退货；可查询物流，拦截或异常问题由人工核验。";
    } else if (inWindow) {
      code = "RETURNABLE"; message = "签收后七日内，可申请退货退款；须客户确认，再由管理员核验审核。";
    } else {
      code = "MANUAL_REVIEW"; message = "超出售后自助窗口，或缺少有效签收记录；保修、故障等问题仍可咨询客服并申请人工核验。";
    }
    var result = new LinkedHashMap<String, Object>();
    result.put("canCancel", status.equals("PAID") && !pending);
    result.put("canApplyRefund", canApply);
    result.put("canConfirmRefund", draft && inWindow);
    result.put("canWithdrawRefund", draft || pending);
    result.put("daysSinceDelivery", days);
    result.put("code", code);
    result.put("message", message);
    return result;
  }
}
