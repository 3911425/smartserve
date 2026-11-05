# 开发与贡献

## 环境与源码入口

Java 17/21、Maven 3.9、Node.js 22。后端与Agent均在 `backend/src/main/java/cn/smartserve`，前端入口为 `frontend/src/App.vue`；项目原标识用于兼容数据与配置。

| 模块 | 关键入口 |
| --- | --- |
| 认证与权限 | security/AuthService、Access、api/AuthController |
| 交易与售后 | business/CommerceService、OrderServicePolicy、FulfillmentSimulator |
| 任务与编排 | agent/RunJournal、RunDispatcher、AgentOrchestrator、ExecutionScope |
| 模型与工具 | agent/ModelGateway、McpBridge、ToolRegistry、api/McpController |
| 知识 | rag/KnowledgeService、data/knowledge |
| 数据迁移 | backend/src/main/resources/db/migration |
| 验证 | backend/src/test、scripts/evaluate-*、data/evaluation |

## 提交变更

先在Issue或PR描述问题、触发条件和验收范围；修改后执行针对回归及必要构建。PR说明写清最终行为、验证结果和仍有的限制，不以概念或技术数量代替证据。

```powershell
cd backend
mvn verify
cd ../frontend
npm ci
npm run build
```

资金与权限变更需覆盖重复请求、状态/版本冲突、事务回滚与越权；Agent变更需覆盖工具边界和失败回退；资料或检索变更需重建隔离索引并跑固定回归。标准基础设施和付费模型均需显式开启，详见[验证报告](06-评测与验证报告.md)。

## 数据与配置

交易表的SQL `TIMESTAMP`及售后日历窗口统一使用 `Asia/Shanghai`。应用入口会设置业务时区，绕过入口的Spring集成测试由Surefire启动参数使用相同时区；标准栈CI显式连接其Redis服务端口6379，日常Compose对本机映射仍为16379。

- 使用隔离数据库和测试队列，不清理业务库或消费业务队列来跑测试。
- 已执行的Flyway迁移不修改，新结构通过新增迁移演进。
- `.env`、数据库、日志、私有学习资料和真实用户数据不提交；示例配置只保留占位或本地开发值。
- 工具不得新增未经业务服务校验的财务写操作。模型输入不能作为用户身份或管理员授权。
- 公开报告同时记录样本、环境、知识revision、模型和限制，保留可比较的历史结果。

公网部署和漏洞处理见[运维文档](07-运维与故障演练.md)。提交Issue时不要附密钥、密码或客户个人资料。
