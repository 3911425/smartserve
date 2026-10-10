# 部署与运维手册

## 日常确认

本地前端5173、Java8080、PostgreSQL5432、Redis6379、RabbitMQ5672和管理页15672。打开/api/status看到模型模式、文档与向量存储；Actuator/health用于基础运行检查。标准栈要确认pgvector标签、Redis/Rabbit连接，不只确认首页能打开。

首次启动会读文档、加载中文ONNX模型、建索引和初始化样例商品订单。文档40个小节片段，后续通过内容哈希复用向量。不要在多个请求同时运行重新索引和修改源文件；索引更新需要管理员发起。

## 配置与变更

模型名、Key、trace开关和数据库连接调整后重启Java。源知识文档修改后点击管理员知识库「重新索引」，索引版本会改变。商品业务目录和手册分别维护，正式业务需发布审批防止规格冲突。

当前建表使用幂等schema.sql，适合初始项目；已有表的结构升级不会自动迁移字段。生产变更应版本化迁移，备份、兼容升级和回滚脚本一起评审。

## 备份和恢复

PostgreSQL逻辑备份示例（凭证由本地工具安全输入，不放在命令参数）：

```powershell
pg_dump -U postgres -h localhost -d smartserve -Fc -f smartserve-backup.dump
# 恢复到一个新建的测试库，验证结果后再切换：
pg_restore -U postgres -h localhost -d smartserve_restore smartserve-backup.dump
```

备份业务数据库、data知识原文和图片；检查点、消息、运行事件随数据库备份。Redis是可重建缓存，Rabbit丢失唤醒时持久QUEUED任务可重投。不能只备份浏览器localStorage或向量列。

H2模式先正常停止后端，再复制backend/data/runtime/smartserve-demo.mv.db。不将数据库文件提交到Git。备份文件含会话与联系方式，保存在受限目录并按保留策略处理。

## 常见故障处理

| 表现            | 检查                        | 操作                             |
| --------------- | --------------------------- | -------------------------------- |
| 页面未连接      | 8080状态、Java日志          | 启动后端、确认profile与数据目录  |
| PG认证失败      | DB_USER/DB_PASSWORD和库名   | 只更改本项目配置，重启应用       |
| vector扩展缺失  | pg_available_extensions     | 选postgres-local或带pgvector容器 |
| 任务一直QUEUED  | Rabbit连接/Worker开关       | 修复队列后等持久任务重派         |
| 任务RUNNING超时 | 最后事件、模型网络          | 等租约恢复；保留检查点分析       |
| 模型降级        | Key、配额、超时与引用       | 修复模型，不删业务数据           |
| 追踪失败        | LangSmith endpoint/key/项目 | 本地事件仍完整，修复后再验证     |
| 工单更新409     | version和当前状态           | 刷新记录后重试，避免覆盖同事处理 |
| 图片404         | images目录与URL             | 恢复素材，不把整个data目录公开   |

## API和Worker分离

同一个Spring Boot制品可运行多个实例，标准模式都连接同一数据库和RabbitMQ。API专用实例设置WORKER_ENABLED=false，Worker实例设置true并监听不同SERVER_PORT。McpBridge调用本实例的内部HTTP端口，所以Worker仍需要自身HTTP服务。本版未提供独立的无HTTPWorker制品。

SSE读取数据库事件，所以API不必和某任务的Worker是同一实例。需要一致的签名secret和MCP服务token。负载均衡与水平扩展前先解决租约fencing、用户限流、连接上限、SQL索引和认证问题。

## 发布前需要真实验证的事项

关闭演示角色切换，接商业身份与用户/租户映射。设置随机机密并限制内部端点。采用HTTPS、备份恢复演练、依赖升级策略、日志保留、告警与压测。接支付/物流时新增回调、幂等、对账与状态规则，不让模型代替业务审批。

当前端口和凭证配置面向单机开发。不能原样把开放管理员切换暴露在公网并宣称企业生产就绪。项目提供工程设计与验证路径，实际发布应以部署环境的证据为准。
