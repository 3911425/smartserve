package cn.smartserve.agent;

import cn.smartserve.business.BusinessService;
import cn.smartserve.rag.KnowledgeService;
import cn.smartserve.security.Access.Actor;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ToolRegistry {

  private final BusinessService business;
  private final KnowledgeService knowledge;
  private static final Map<String, Set<String>> ALLOWED = Map.of(
    "PRODUCT",
    Set.of("searchKnowledge", "getProducts"),
    "ORDER",
    Set.of("queryOrderStatus"),
    "AFTER_SALES",
    Set.of(
      "searchKnowledge",
      "queryOrderStatus",
      "checkRefundEligibility",
      "createRefundDraft"
    )
  );

  public ToolRegistry(BusinessService business, KnowledgeService knowledge) {
    this.business = business;
    this.knowledge = knowledge;
  }

  public Set<String> allowed(String agent) {
    return ALLOWED.getOrDefault(agent, Set.of());
  }

  public Object call(
    String agent,
    Actor actor,
    String tool,
    Map<String, Object> args
  ) {
    if (!allowed(agent).contains(tool)) throw new SecurityException(
      "当前 Agent 未被授权调用该工具"
    );
    return switch (tool) {
      case "searchKnowledge" -> searchKnowledge((String) args.get("query"));
      case "getProducts" -> getProducts();
      case "queryOrderStatus" -> business.order(
        actor,
        required(args, "orderId")
      );
      case "checkRefundEligibility" -> business.refundEligibility(
        actor,
        required(args, "orderId")
      );
      case "createRefundDraft" -> business.createRefund(
        actor,
        required(args, "orderId"),
        required(args, "reason")
      );
      default -> throw new IllegalArgumentException("未知工具");
    };
  }

  private String required(Map<String, Object> args, String key) {
    Object v = args.get(key);
    if (
      !(v instanceof String s) || s.isBlank() || s.length() > 500
    ) throw new IllegalArgumentException("工具参数不合法：" + key);
    return (String) v;
  }

  @Tool("检索经过审核的产品、政策和FAQ，返回来源与片段，不足时不得编造")
  public Object searchKnowledge(@P("客户问题，最多2000字符") String query) {
    if (
      query == null || query.length() > 2000
    ) throw new IllegalArgumentException("查询不合法");
    return knowledge.search(query, 4);
  }

  @Tool("读取当前商品目录和已知规格")
  public Object getProducts() {
    return business.products();
  }

  public List<Map<String, Object>> definitions(String agent) {
    return allowed(agent)
      .stream()
      .sorted()
      .map(name -> {
        String description = switch (name) {
          case "searchKnowledge" -> "检索版本化知识库，返回候选证据、相关性与来源";
          case "getProducts" -> "列出商品目录，不创建订单";
          case "queryOrderStatus" -> "校验用户归属后查询订单事实";
          case "checkRefundEligibility" -> "确定性检查退款窗口与状态";
          default -> "创建幂等待确认退款草稿，不能确认或转账";
        };
        Map<String, Object> props = switch (name) {
          case "searchKnowledge" -> Map.of(
            "query",
            Map.of("type", "string", "maxLength", 2000)
          );
          case "getProducts" -> Map.of();
          case "createRefundDraft" -> Map.of(
            "orderId",
            Map.of("type", "string"),
            "reason",
            Map.of("type", "string", "maxLength", 500)
          );
          default -> Map.of("orderId", Map.of("type", "string"));
        };
        return Map.<String, Object>of(
          "name",
          name,
          "description",
          description,
          "inputSchema",
          Map.of(
            "type",
            "object",
            "properties",
            props,
            "required",
            props.keySet(),
            "additionalProperties",
            false
          )
        );
      })
      .toList();
  }
}
