package cn.smartserve.agent;

import cn.smartserve.business.*;
import cn.smartserve.rag.KnowledgeService;
import cn.smartserve.security.Access.Actor;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import java.util.*;
import org.springframework.stereotype.Service;

/** The same catalogue powers MCP discovery and native model tool calling. All agent tools are read-only. */
@Service
public class ToolRegistry {

  private final CommerceService commerce;
  private final KnowledgeService knowledge;
  private static final Map<String, Set<String>> ALLOWED = Map.of(
    "PRODUCT",
    Set.of("searchKnowledge", "getProducts"),
    "ORDER",
    Set.of("queryOrderStatus", "listMyOrders"),
    "AFTER_SALES",
    Set.of(
      "searchKnowledge",
      "queryOrderStatus",
      "listMyOrders",
      "checkRefundEligibility",
      "prepareRefund"
    )
  );

  public ToolRegistry(CommerceService commerce, KnowledgeService knowledge) {
    this.commerce = commerce;
    this.knowledge = knowledge;
  }

  public Set<String> allowed(String agent) {
    return ALLOWED.getOrDefault(agent, Set.of());
  }

  private List<String> parameters(String name) {
    return switch (name) {
      case "searchKnowledge" -> List.of("query");
      case "queryOrderStatus", "checkRefundEligibility" -> List.of("orderId");
      case "prepareRefund" -> List.of("orderId", "reason");
      default -> List.of();
    };
  }

  private String description(String name) {
    return switch (name) {
      case "searchKnowledge" -> "检索审核后的产品手册、交易及售后政策。必须使用accepted=true的证据；可改写问题再次检索。";
      case "getProducts" -> "读取当前商品型号、价格、可售库存和规格，不能下单。";
      case "listMyOrders" -> "读取当前客户最近的订单摘要，用于澄清用户指的是哪一笔，不能替用户随意选择。";
      case "queryOrderStatus" -> "核验订单归属后读取订单事实和模拟物流，不返回收货地址或联系方式。";
      case "checkRefundEligibility" -> "由业务规则检查订单的七日窗口、状态和资金来源，模型不能修改结论。";
      case "prepareRefund" -> "准备只读的售后建议卡，不创建申请、不扣款、不退款。用户必须通过界面确认才能提交申请。";
      default -> throw new IllegalArgumentException("未知工具");
    };
  }

  public List<ToolSpecification> specifications(String agent) {
    return allowed(agent)
      .stream()
      .sorted()
      .map(name -> {
        var schema = JsonObjectSchema.builder().additionalProperties(false);
        for (String p : parameters(name)) schema.addStringProperty(p);
        schema.required(parameters(name));
        return ToolSpecification.builder()
          .name(name)
          .description(description(name))
          .parameters(schema.build())
          .build();
      })
      .toList();
  }

  public List<Map<String, Object>> definitions(String agent) {
    return allowed(agent)
      .stream()
      .sorted()
      .map(name -> {
        var properties = new LinkedHashMap<String, Object>();
        for (String p : parameters(name))
          properties.put(
            p,
            Map.of(
              "type",
              "string",
              "maxLength",
              p.equals("query") ? 2000 : 500
            )
          );
        return Map.<String, Object>of(
          "name",
          name,
          "description",
          description(name),
          "inputSchema",
          Map.of(
            "type",
            "object",
            "properties",
            properties,
            "required",
            parameters(name),
            "additionalProperties",
            false
          )
        );
      })
      .toList();
  }

  public Object call(
    String agent,
    Actor actor,
    String name,
    Map<String, Object> args
  ) {
    if (!allowed(agent).contains(name)) throw new SecurityException(
      "当前角色未被授权调用该工具"
    );
    if (
      !new HashSet<>(parameters(name)).containsAll(args.keySet())
    ) throw new IllegalArgumentException("工具包含未授权参数");
    // A privileged operator cannot use the assistant to impersonate another customer.
    var customer = new Actor(actor.userId(), false);
    return switch (name) {
      case "searchKnowledge" -> knowledge.search(
        required(args, "query", 2000),
        4
      );
      case "getProducts" -> commerce.products();
      case "listMyOrders" -> commerce
        .orders(customer)
        .stream()
        .limit(8)
        .map(o ->
          Map.of(
            "id",
            o.get("id"),
            "productName",
            o.get("productName"),
            "status",
            o.get("status"),
            "createdAt",
            o.get("createdAt")
          )
        )
        .toList();
      case "queryOrderStatus" -> commerce.toolOrder(customer, orderId(args));
      case "checkRefundEligibility" -> commerce.refundEligibility(
        customer,
        orderId(args)
      );
      case "prepareRefund" -> commerce.prepareRefund(
        customer,
        orderId(args),
        required(args, "reason", 500)
      );
      default -> throw new IllegalArgumentException("未知工具");
    };
  }

  private String orderId(Map<String, Object> args) {
    String id = required(args, "orderId", 40).toUpperCase(Locale.ROOT);
    if (!id.matches("SS\\d{11,16}")) throw new IllegalArgumentException(
      "请使用完整订单号"
    );
    return id;
  }

  private String required(Map<String, Object> args, String key, int max) {
    Object value = args.get(key);
    if (
      !(value instanceof String s) || s.isBlank() || s.length() > max
    ) throw new IllegalArgumentException("工具参数不合法：" + key);
    return s.strip();
  }
}
