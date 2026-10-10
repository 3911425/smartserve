# 从一条请求读懂项目

## 建议阅读顺序

先运行一遍商品问答→退款确认→人工回写，看到行为后再读代码。不要一开始从所有框架API读起。IDEA以backend/pom.xml打开，前端在frontend/src。

| 顺序 | 文件                                                                  | 你需要回答的问题                                        |
| ---- | --------------------------------------------------------------------- | ------------------------------------------------------- |
| 1    | SmartServeApplication.java、application.yml                           | Key从哪里来？为什么环境变量优先？profile怎么切？        |
| 2    | api/ApiController.java、security/Access.java                          | 当前用户是谁？在什么地方检查数据归属？                  |
| 3    | business/BusinessService.java                                         | 为什么先锁订单？哪些操作需要事务？确认为什么单独接口？  |
| 4    | agent/RunJournal.java、RunDispatcher.java                             | API如何快速返回？重复消息怎样避免重复执行？             |
| 5    | agent/AgentOrchestrator.java                                          | 图上每个节点的输入输出、允许路径与结束条件是什么？      |
| 6    | agent/ToolRegistry.java、api/McpController.java、agent/McpBridge.java | 工具如何发现？谁校验Agent权限和用户归属？               |
| 7    | rag/KnowledgeService.java                                             | 文档如何成为向量？BM25式分数、RRF、重排、门槛各做什么？ |
| 8    | agent/JdbcCheckpointSaver.java                                        | thread_id为什么用run_id？恢复从哪个节点开始？           |
| 9    | agent/ModelGateway.java、TraceExporter.java                           | 模型失败怎样降级？远程追踪怎样脱敏？                    |
| 10   | frontend/src/api.ts、App.vue、style.css                               | SSE怎样携带鉴权和重连？界面如何使用真实终态？           |

## 链路一：门锁没电的问答

App.vue 的 `ask`切换客服页，`send`新建或复用会话，生成requestKey并提交`/runs`。Controller在身份过滤器之后执行，RunJournal核验会话、锁行、持久任务和用户消息。

Dispatcher唤醒Worker；consume通过SQL条件更新拿到执行权。图的Supervisor路由到PRODUCT，product节点经过McpBridge调用searchKnowledge。HTTP请求携带内部服务凭证与可信用户，McpController校验服务凭证，ToolRegistry检查PRODUCT允许的工具，再调用KnowledgeService。

KnowledgeService把问题向量化，向量通道和稀疏通道召回，重排和门槛过滤。图保留证据片段及其来源。Guardrail只拿证据进行生成与独立校验；原文、引用检查和模型异常都有明确回退。

RunJournal把最终结果与assistant消息在事务里保存，并产生completed事件。前端SSE读取事件后重新加载持久消息。AgentOps看到的是同一run_event，不是另造的演示日志。

## 链路二：为什么退款不能让LLM做主

模型能判断用户在问退款，但订单是否属于用户、是否在7日窗口、已退款没有、金额是多少，都应由业务代码决定。AfterSales调用createRefundDraft只得到草稿，工具注册表里没有用户确认或真实支付工具。

BusinessService先核验所有权，再锁订单行，查已有申请，然后检查资格并插入唯一记录。16个并发测试验证了不是只靠一个Java内存Map。不同实例共享数据库锁和唯一约束。

用户点击确认才进入confirmRefund，再检查一次业务状态。确认可重试；APPROVED返回原结果。不把“申请确认”写成“资金到账”。真正支付还需要外部幂等、回调与对账，这些没有假装实现。

## 链路三：人工为什么用服务升级单

AI只提出移交，用户表单明确提交才创建工单。工单ID由用户、会话和请求键生成，重复点击是同一条记录。管理员认领与完成属于确定性的状态机。

更新时版本匹配才写入，避免两个人相互覆盖。完成结果和会话回复在同一事务内写入。内部notes对客户过滤，客户看到的只是问题、状态和处理结论。

## 三种数据不要混淆

Markdown是原始业务内容，向量是检索索引，chat_message是客户对话，graph_checkpoint是执行现场。原文不是聊天记忆，向量不是订单表，Redis缓存不是历史的唯一副本。打开数据库看一次这些表，区别就很直观。

## 最值得自己动手的练习

1. 增加一款虚构设备：更新SeedData与产品手册，理解商品业务数据和知识内容为什么分别存在。
2. 设计“部分退款”：先画金额与状态模型，再改业务和测试，不先让模型自由填写金额。
3. 扩展负样本：问不存在的产品能力、外品牌兼容和无关知识，调门槛并用保留测试集验证。
4. 让服务单支持“等待客户材料”：先明确允许的状态迁移，再加界面操作、版本更新和回写测试。
5. 使用标准Docker组件栈，关闭某个Worker或Redis，观察任务与历史是否仍可恢复。
6. 替换一个领域专家的系统提示，比较路由和审查结果；不要同时改变全部变量。

每项练习保留一个真实提交和一段说明：问题、修改、验证、限制。这样代码是你能够解释的项目，而不是只能启动的成品。

## 阅读代码时常见误区

MCP不等于业务权限：协议提供标准调用形状，ToolRegistry和BusinessService才执行授权。多Agent不等于多个模型自由聊天：此处是受控域专家的状态图。Checkpoint不等于对话记忆：一个恢复执行，一个恢复用户历史。相似度不是正确率：门槛需用样本标定。LangSmith追踪不自动提高质量：它帮助定位问题，质量仍依赖证据和业务规则。

## 如何诚实展示贡献

本版由AI辅助生成。你应先完成自己的调试、理解、修改与评估，再说明自己负责的设计和改进。不要声称所有源码完全独立手写、已经承载商业流量、已经实现真实支付，或引用没有测过的指标。能讲透一个并发幂等测试，通常比堆十个技术名称更有价值。
