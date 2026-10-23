# SmartServe 智服

文档更新：2026-10-23 · 应用版本 `2.0.0` · 业务规则版本 `2.1-20261023`。应用包名仍是 `smartserve-backend-2.0.0.jar`，业务规则版本用于检查前后端是否匹配，两者用途不同。

以 AI 客服为核心的智能家居商城与服务平台。客户可以注册、使用模拟钱包购买商品、查询订单、申请售后；管理员按客户处理订单、退款和人工服务。产品与售后 Agent 通过受限工具自主查询，资金变动始终由确定性事务处理。

这是可运行、可评测的模块化单体项目，**支付、物流、商品品牌均为模拟业务**。没有真实支付、真实承运商、邮件找回、多模态或微服务，不以框架数量代替业务完成度。

## 从这里开始

1. [启动与账号](docs/01-启动与账号.md)：只记住三种常用启动方式。
2. [产品与业务规则](docs/02-产品与业务规则.md)：先用客户和管理员走通一个闭环。
3. [项目学习实战手册](docs/项目学习实战手册.md)：按源码、调试、实验和验收学习。
4. [项目发展路线](docs/项目发展路线.md)：当前版本如何冻结，冬实习后如何继续。
5. [评测与验证报告](docs/06-评测与验证报告.md)：看实际通过哪些验证、指标怎样计算、还有什么没测。

需要深入时读：[架构与数据模型](docs/03-架构与数据模型.md) → [Agent 与 RAG](docs/04-Agent与RAG实现.md) → [接口与权限](docs/05-接口与权限.md) → [运维与故障演练](docs/07-运维与故障演练.md) → [学习验收与面试追问](docs/学习验收题与面试追问.md)。上面是全部现行项目说明文档；旧的“业务一致性修复与验收”已合并进业务规则、验证报告和运维文档。

应用实际检索的四份资料：[产品手册](data/knowledge/产品手册.md)、[客户 FAQ](data/knowledge/FAQ.md)、[售后政策](data/knowledge/售后政策.md)、[交易与账号](data/knowledge/交易与账号.md)。它们是虚构品牌的客服知识，不是额外实现了设备 App、云录像或线下仓库。

## 本机快速启动

需要 Java 17/21、Maven 3.9、Node.js 22；本机配置使用 PostgreSQL 时先确认服务运行。Docker 不是这种方式的前提。

```powershell
cd E:\SmartServe
powershell -ExecutionPolicy Bypass -File scripts/start.ps1
```

访问 http://localhost:5173 。脚本会初始化本地凭证、构建后端、按需安装前端依赖，并启动前后端两个进程。首次加载 BGE 索引需要等待，日志在 `.local/`。当前机器的 `.env` 继续保留原有数据库和模型配置；没有把 API Key 写入代码。

首次管理员账号为 `.env` 中的 `BOOTSTRAP_ADMIN_USER`，密码在 `BOOTSTRAP_ADMIN_PASSWORD`；历史客户 `demo` 的初始密码在 `BOOTSTRAP_CUSTOMER_PASSWORD`。新客户可以直接注册。已存在数据库中的密码不会被环境文件覆盖，后续改密后以新密码为准。

```powershell
powershell -ExecutionPolicy Bypass -File scripts/stop.ps1
```

## 实现范围

| 部分 | 已实现                                                                                                 |
| ---- | ------------------------------------------------------------------------------------------------------ |
| 账户 | BCrypt、可撤销 Cookie 会话、CSRF、登录失败锁定、资料与密码、客户密码重置                               |
| 交易 | 一次性模拟余额、幂等购买、随机且持久化的配送场景、统一售后资格、取消、退款确认/撤回/重提/审核、版本历史 |
| 客服 | 产品/售后原生工具调用循环、确定性订单节点、LangGraph4j 检查点、真实 HTTP MCP、证据审查、人工服务单     |
| RAG  | 本地 BGE-small-zh 512 维、分块及版本元数据、BM25/向量/混合检索、产品范围过滤、证据门槛、Redis 结果缓存 |
| 运行 | 本地工作池或 RabbitMQ、持久任务与租约、恢复/取消/SSE、每次模型请求遥测、脱敏 LangSmith 导出、答案反馈  |
| 评测 | 事务/权限/并发集成测试、SDK 工具调用协议测试、固定检索/任务集、自定义模型评审脚本                      |

Java 后端和 Agent 均用 Java：Spring Boot、JdbcTemplate、LangChain4j、LangGraph4j；前端使用 Vue 3 / TypeScript。当前对话模型通过 TokenDance 调用 `deepseek-v4.1-flash`，向量模型为本地 BGE-small-zh，不消耗远程对话 Token。

最新结果统一看[评测与验证报告](docs/06-评测与验证报告.md)。原始历史记录保留作对照，并明确标记模型和知识版本；AgentOps 的轨迹不能替代质量评测。

## 构建与验证

```powershell
cd backend
mvn verify
cd ../frontend
npm ci
npm run build
```

默认测试使用隔离 H2 和本地模型协议桩，不消耗远程模型额度。两套 PostgreSQL 和 Redis/RabbitMQ 的一致性回归使用 `scripts/test-business-modes.ps1`，前置条件、隔离方式和评测命令见验证报告。构建成功后程序会退出，日常运行仍用启动脚本。

代码入口：[Java 后端](backend/src/main/java/cn/smartserve)、[前端 App](frontend/src/App.vue)、[数据库迁移](backend/src/main/resources/db/migration)、[评测资料](data/evaluation)。密钥、私有密码、日志和数据库备份放在被忽略的 `.env` / `.local`，不要提交到 Git。

## 页面预览

![登录页](docs/screenshots/auth-v21.png)

![商城](docs/screenshots/store-v21.png)

![订单概览](docs/screenshots/orders-v21.png)
