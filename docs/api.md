# API 使用说明

开发代理入口 `http://localhost:5173/api`，Java入口 `http://localhost:8080/api`。JSON使用UTF-8，除状态和演示会话外需 `Authorization: Bearer <token>`。凭证不要放URL或日志。

## 身份与目录

| 方法 | 路径                         | 说明                                                  |
| ---- | ---------------------------- | ----------------------------------------------------- |
| GET  | /status                      | 模型模式、知识索引、模拟资金标识，无密钥              |
| POST | /demo/session                | `{ "role":"customer" }` 或 admin；仅DEMO_ACCESS开启时 |
| GET  | /products                    | 商品目录                                              |
| GET  | /orders                      | 客户本人订单 / 管理员全部                             |
| GET  | /orders/{id}                 | 单笔订单并校验归属                                    |
| POST | /conversations               | 新建会话                                              |
| GET  | /conversations               | 当前身份的会话列表                                    |
| GET  | /conversations/{id}/messages | 原会话消息，含assistant或human结果卡片                |

演示会话返回 token、userId、role、demo。token使用HMAC签名，有效12小时。关闭演示后，服务器识别预配置 CUSTOMER_API_TOKEN / ADMIN_API_TOKEN；真实身份服务尚未接入。

## Agent运行与SSE

创建任务：

```json
{
  "conversationId": "conv-由服务端创建",
  "question": "门锁没电了怎么应急开门？",
  "requestKey": "客户端生成并在重试时复用的UUID"
}
```

`POST /runs` 返回 id、status、traceId。输入最大2000字符，requestKey最大100。`GET /runs`列出可访问运行，`GET /runs/{id}`查询状态与终态结果，`GET /runs/{id}/events?after=0`查询增量事件。

`GET /runs/{id}/stream` 返回 `text/event-stream`，支持 `Last-Event-ID`：

```text
id: 42
event: routing
data: {"eventId":42,"type":"routing","agent":"Supervisor","routes":["PRODUCT"],"label":"意图识别 → PRODUCT","durationMs":10,"status":"ok"}

id: 48
event: completed
data: {"eventId":48,"type":"completed","result":{"answer":"... [S1]","sources":[],"routes":["PRODUCT"],"tokens":0,"needsEscalation":false}}
```

事件类型包括queued、routing、tool、retrieval、validation、handoff、checkpoint、fallback、completed、failed、trace_export。UI使用fetch流式读取，以便携带Bearer头；浏览器原生EventSource不直接支持这种头配置。

当前SSE传输真实节点进度和最终回答，没有伪装字符分帧成模型Token流。客户断开不会取消已开始的持久业务任务。连接超时240秒，最多64连接；断线补发按eventId去重，最终状态仍可通过GET轮询。

## 退款

| 方法 | 路径                  | 输入 / 行为                                    |
| ---- | --------------------- | ---------------------------------------------- |
| POST | /refunds              | `{ "orderId":"SS...", "reason":"无理由退货" }` |
| GET  | /refunds/{id}         | 核对本人申请，返回paymentMode=SIMULATED        |
| POST | /refunds/{id}/confirm | `{ "confirmed":true }`，重新核验后确认         |

没有amount输入，不允许修改金额；草稿创建不会改变订单状态。repeat调用返回同一申请；确认可重复，已APPROVED不再重复改变状态。资格不符返回409，并建议人工核验。

## 服务升级单

```json
{
  "conversationId": "conv-...",
  "orderId": "",
  "title": "门锁安装适配咨询",
  "details": "门厚与锁体需要人工核验",
  "contact": "example@example.invalid",
  "priority": "NORMAL",
  "requestKey": "稳定的提交UUID",
  "confirmed": true
}
```

`POST /tickets`必须显式confirmed；title100字符、details2000、contact100。`GET /tickets`客户仅本人且没有内部notes，管理员可全部。`PUT /tickets/{id}`只允许管理员：

```json
{
  "status": "PROCESSING",
  "resolution": "",
  "note": "已联系安装专员核验",
  "version": 0
}
```

完成时status=RESOLVED且resolution非空，使用最新version。结果回写原conversationId。旧version、非法流转返回409。

## 知识库

`GET /knowledge`返回三份可读资料和片段数。`GET /knowledge/search?query=...&strategy=hybrid`返回最多5个候选。strategy允许hybrid、vector、bm25；字段含id、source、heading、content、score、semanticScore、lexicalScore、accepted、citation。分数不是概率。`POST /knowledge/reindex`仅管理员，用于文档更新后重建索引。

## MCP服务接口

内部工具端点`POST /api/mcp`需要独立服务Bearer凭证，X-SmartServe-Agent选择工具域，X-SmartServe-User是可信调用者上下文；普通用户的会话token不能当MCP服务token。标准Nginx将此路径拒绝给浏览器。

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {
    "protocolVersion": "2025-03-26",
    "capabilities": {},
    "clientInfo": { "name": "example", "version": "1.0" }
  }
}
```

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/call",
  "params": {
    "name": "queryOrderStatus",
    "arguments": { "orderId": "SS20261010002" }
  }
}
```

tools/list按Agent返回受限目录。业务工具错误以content文本和isError=true返回；未知JSON-RPC方法返回-32601。不允许的工具在注册表层拒绝，不由模型自行判断权限。

## 错误语义

400：字段、确认或枚举错误。401：缺失、过期或无效凭证。403：他人记录、管理员权限或关闭的演示接口。404：记录不存在。409：状态、窗口、并发版本或请求键冲突。429：SSE资源上限。500：服务未完成，不向客户端回显SQL、Key或堆栈。
