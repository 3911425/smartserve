export type Entity = Record<string, any>;
export const money = (value: unknown) =>
  Number(value ?? 0).toLocaleString("zh-CN", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
export const date = (value: unknown) =>
  value
    ? new Date(String(value).replace(" ", "T")).toLocaleString("zh-CN", {
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
        hour12: false,
      })
    : "—";
export const labels: Record<string, string> = {
  PAID: "待发货",
  SHIPPED: "运输中",
  DELIVERED: "已送达",
  CANCELLED: "已取消",
  REFUND_PENDING: "售后审核中",
  REFUNDED: "已退款",
  AWAITING_CONFIRMATION: "待客户确认",
  PENDING_REVIEW: "待管理员审核",
  REJECTED: "未通过",
  PENDING: "待处理",
  PROCESSING: "处理中",
  RESOLVED: "已完成",
  QUEUED: "排队中",
  RUNNING: "执行中",
  COMPLETED: "已完成",
  FAILED: "失败",
  SUCCESS: "成功",
  ERROR: "请求失败",
  WELCOME: "初始体验赠金",
  PAYMENT: "模拟购买",
  CANCELLATION: "取消返还",
  REFUND: "售后退款",
  PRODUCT: "产品与使用咨询",
  ORDER: "订单查询",
  AFTER_SALES: "客户服务",
  ESCALATE: "人工服务",
};
