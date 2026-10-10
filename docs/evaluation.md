# 测试与评估记录

验证日期：2026-10-10。只记录实际执行和可重复步骤，不用目标值代替实测结果。

## 已完成的本机验证

| 验证 | 结果 / 环境 |
|---|---|
| 后端编译与可运行JAR | Maven构建通过，本机Java17兼容profile |
| 自动集成测试 | 12项通过；2项标准组件测试默认跳过，交由独立CI环境 |
| 前端类型与构建 | vue-tsc + Vite通过 |
| 中文Embedding | BGE中文ONNX实际运行，512维，3文档40片段 |
| PostgreSQL | 本机PG18连接成功，创建专用smartserve库并持久保存业务、会话、事件与检查点 |
| DeepSeek实际问答 | 真实调用，约1400余Token的样例问题，引用白名单和独立证据审查通过 |
| LangSmith | 修正dotted_order后，脱敏根追踪与子事件导出成功 |
| 浏览器商城 | 页面图片、导航与商品搜索验证，保存截图 |
| 浏览器人工闭环 | 提交→认领→内部备注→完成；客户接口确认收到human回复且无notes字段 |
| Windows启停脚本 | Windows PowerShell5运行，使用UTF-8 BOM保证中文脚本可解析 |

测试重点包括：跨用户订单访问、16并发整单退款草稿、重复确认、窗口过期、用户确认、工单非法流转、乐观锁冲突、人工回写、Agent工具权限、未知问题拒答、MCP握手与工具发现、Origin拒绝、签名篡改、数据库Saver新实例恢复、管理员代客户登记的归属。

## 标准组件CI

仓库提供`StandardStackIntegrationTest`：实际PG+pgvector512维向量、Redis写读、RabbitMQ消费持久任务、MCP调用和Checkpoint保存。GitHub Actions的standard-stack job启动三个容器并使用Java21。

云端实际验证已通过：[GitHub Actions 38020051886](https://github.com/3911425/smartserve/actions/runs/38020051886)。backend、frontend、standard-stack三个Job均成功；Java21下12项基础集成测试通过，PG+pgvector、Redis、RabbitMQ的2项标准栈测试通过。完整Docker Compose应用镜像部署尚未在本机执行，不能把组件集成测试等同于公网生产部署。

## 检索对比实验

数据集`data/evaluation/retrieval-cases.json`有17个正例、3个无关负例，标签为相关二级标题。Recall@5按每题命中的相关标题比例平均，MRR@5按首个相关标题倒数排名平均。标签并非全部相关事实的穷尽集合。

```powershell
node scripts/evaluate-retrieval.mjs
```

脚本使用本地客服身份，仅检索，不调用付费LLM；生成`docs/retrieval-results.json`。原始基线保存在`docs/retrieval-baseline.json`。

基线：vector Recall@5=0.8529、MRR@5=0.9118；bm25式词法 Recall@5=0.9706、MRR@5=0.7078；初始hybrid Recall@5=0.9706、MRR@5=0.7304。混合提高该样例集的标签覆盖，但首位排序不如纯向量，不能笼统声称“所有指标更优”。

基线还发现“怎样治疗流感并开处方药？”误命中“怎样查订单和物流？”等通用FAQ。改进增加智能家居/订单/售后业务域门槛，并将重排权重从0.60/0.30/0.10改为0.72/0.23/0.05。最终结果见JSON与后续本节补充。

权重选择使用同一小样例集的候选对比，属于开发调参；尚未分离独立保留测试集。负样本门槛对三个排序策略使用同一规则，不能将拒答差异归功于排序算法。只有3个负例，不足以证明广泛安全性或稳定泛化。



最终实测（本机 PostgreSQL / JVM 中文向量，17正例、3负例）：

| 策略 | Recall@5 | MRR@5 | 无关问题误接受 |
|---|---:|---:|---:|
| vector | 0.8529 | 0.9118 | 0/3 |
| bm25 | 0.9706 | 0.7078 | 0/3 |
| hybrid | 0.9706 | 0.8186 | 0/3 |


## RAGAS可选评估

已提供独立脚本和固定依赖文件，但没有伪造Faithfulness成绩。本版未实际完成RAGAS模型评估。建议使用Python3.11独立环境，评估会发真实模型请求并下载中文Embedding模型：

```powershell
python -m venv .venv
.venv\Scripts\Activate.ps1
pip install -r data/evaluation/requirements-ragas.txt
python scripts/evaluate-ragas.py
```

先从实际客服结果整理`data/evaluation/answers.json`，每行应有question、answer、contexts数组和人工审核的ground_truth。不能把理想回答或模型编造的证据塞进评估集。脚本运行后保存真实CSV，检查失败/NaN行，再人工抽查。当前固定版本的脚本未进行环境安装验证，升级或安装时须确认依赖兼容性。

## 尚未完成的验证

没有商业压测、完整浏览器自动回归、支付/物流真实接口、跨节点网络分区、长暂停Worker恢复fencing验证、独立测试集的幻觉攻击评估或公开公网部署。没有百万并发、商业SLA、RAGAS提升分数等没有证据的声明。

## 技术参考

- [LangGraph4j项目与API](https://github.com/langgraph4j/langgraph4j)
- [LangChain4j内嵌Embedding模型](https://github.com/langchain4j/langchain4j-embeddings)
- [DeepSeek Chat Completions接口](https://api-docs.deepseek.com/api/create-chat-completion/)
- [MCP Streamable HTTP规范](https://modelcontextprotocol.io/specification/2025-03-26/basic/transports)
- [LangSmith REST追踪说明](https://docs.langchain.com/langsmith/trace-with-api)
- [RAGAS源码与版本](https://github.com/explodinggradients/ragas)

依赖版本与本项目选择是一组可构建组合，不代表这些组件的最新版本。生产升级前需做兼容与安全验证。
