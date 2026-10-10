# 运行、配置与 IDEA 指南

## 当前电脑的实际配置

已检测到 Java17、Maven、Node22 和本机 PostgreSQL18。已创建项目专用的 `smartserve` 数据库，用户使用本地 `.env` 中的 PostgreSQL 账户和密码；文档与 GitHub 不保存这些凭证。当前库没有 pgvector，所以本机配置使用 PostgreSQL 持久业务 + Java中文BGE/余弦检索 + 本地有限工作池。DeepSeek与LangSmith均可单独开关。

你本机 `.env` 选择 `SPRING_PROFILES_ACTIVE=postgres-local`。换一台没有 PostgreSQL 的电脑可选择 demo，使用独立的 H2 文件。正式组件栈选 postgres，需要 pgvector、Redis、RabbitMQ。

| 配置           | 需要启动的组件                           | 用途                      |
| -------------- | ---------------------------------------- | ------------------------- |
| demo           | Java后端、Vite前端                       | H2文件与JVM向量，本地学习 |
| postgres-local | 已安装PostgreSQL、Java后端、Vite前端     | 直接使用你的本机数据库    |
| postgres       | PG+pgvector、Redis、RabbitMQ、后端、前端 | 标准部署与基础设施验证    |

## 一键启停

```powershell
cd E:\SmartServe
powershell -ExecutionPolicy Bypass -File scripts/start.ps1
```

首次执行构建后端并安装缺失的前端依赖，随后在后台启动两进程。访问 http://localhost:5173。日志在`.local/backend.out.log`与`.local/frontend.out.log`，首启模型索引可能需要30–90秒。窗口关闭不等于进程停止。

```powershell
# 已经构建过：
powershell -ExecutionPolicy Bypass -File scripts/start.ps1 -SkipBuild
# 只用证据与业务，不发模型请求：
powershell -ExecutionPolicy Bypass -File scripts/start.ps1 -Offline
# 停止本脚本启动的进程：
powershell -ExecutionPolicy Bypass -File scripts/stop.ps1
```

脚本检测8080/5173是否已占用；不强行停止未知进程。停止脚本只处理保存的、命令行包含项目目录的PID，不清数据库。

## 手动运行，便于学习

打开两个终端。第一个：

```powershell
cd E:\SmartServe\backend
mvn package -DskipTests
java -jar target\smartserve-backend-1.0.0.jar
```

第二个：

```powershell
cd E:\SmartServe\frontend
npm ci
npm run dev
```

主类读取项目根目录 `.env`，进程环境变量优先。命令行指定`--spring.profiles.active=demo`可临时换环境，数据库数据各自独立。不要把 `.env` 复制到 resources 后再打包，否则可能将密钥带入JAR。

## IDEA 打开代码

1. File→Open，选择 `E:\SmartServe\backend\pom.xml` 并作为项目打开。
2. Project SDK 建议设为 JDK21；本机暂用17也能通过 Maven自动兼容profile。
3. Maven工具窗 Reload，等待依赖下载。中文BGE模型作为依赖进入本地Maven缓存，首次包下载较大。
4. 运行 `cn.smartserve.SmartServeApplication`；Working directory设为 `E:\SmartServe\backend`，以正确读取`../.env`与`../data`。
5. 需要调试时在 `ApiController.createRun`、`BusinessService.createRefund`、`AgentOrchestrator.supervisor`设置断点。
6. 前端单独在终端 `npm run dev`，Vite热更新；Java源码需重新编译/重启。

较旧IDEA可能不完整支持Java21，先使用项目兼容的17或更新IDEA。业务与图代码没有依赖21专用语法；Docker和CI使用21。

## PostgreSQL 连接工具

在 pgAdmin、DBeaver 或 IDEA Database 中新建 PostgreSQL：Host `localhost`、Port `5432`、Database `smartserve`、User与你本地`.env`相同，Password填本地密码。连接后可看 product、customer_order、chat_message、agent_run、run_event、graph_checkpoint、refund_request、escalation_ticket、knowledge_chunk。

另一台电脑使用本机PG时先创建空数据库smartserve；应用启动会创建项目表与样例数据。可在pgAdmin右键Databases→Create，或者终端 `psql -U postgres -c "CREATE DATABASE smartserve;"`，密码按提示输入。

安装PostgreSQL本身不等于安装pgvector。检查：

```sql
SELECT name, default_version FROM pg_available_extensions WHERE name='vector';
```

结果为空时先选postgres-local，或使用项目提供的pgvector容器。标准模式会执行`CREATE EXTENSION vector`，不允许把没有向量扩展的库伪装成pgvector运行。

## Docker标准部署

确保Docker Desktop已启动，在根目录执行：

```powershell
docker compose --profile app up -d --build
docker compose --profile app ps
docker compose --profile app logs -f backend
```

Compose覆盖SPRING_PROFILES_ACTIVE为postgres。所有服务端口绑定127.0.0.1，避免默认对局域网暴露。Redis/RabbitMQ采用`.env`中的本地凭证，数据库用户名由同一文件确定。

你的本机PG已经占用5432时，Compose的PG容器会冲突。可修改Compose宿主端口为`15432:5432`；容器间仍使用postgres:5432。若手动启动Java连接这个容器，DB_URL用localhost:15432。也可以停本机PG后运行容器，但不要让脚本擅自停其他项目服务。

## 环境变量的含义

DEEPSEEK_API_KEY只用于模型API，LANGSMITH_API_KEY只用于追踪。LLM_ENABLED=false仍有真实检索和确定性业务。LANGSMITH_TRACING=false不影响本地AgentOps。DEMO_ACCESS=true允许演示角色，正式环境关闭；SESSION_SIGNING_SECRET和MCP_SERVICE_TOKEN必须随机且不提交。

DB_USER/DB_PASSWORD用于PostgreSQL，H2演示的凭证独立。SMARTSERVE_DATA_PATH是图片和知识目录，不是数据库。DB_URL连接关系/向量数据库；Redis缓存与Rabbit任务不是表文件。对话和Checkpoints保存在数据库，重启不会依赖浏览器内存保存。

## 常见问题

页面提示未连接：先请求 `/api/status`；检查后端日志和8080端口，再刷新。首次模型还在初始化时等待。

模型降级：核对LLM开关、Key、余额、模型名与网络；不要将完整Key截图发到公开Issue。AgentOps会记录回退，本地数据库仍可查订单。

数据库密码错误：编辑`.env`后重启Java，确认当前profile与DB_USER。修改密码不会自动修改已经创建的Docker卷账号密码，须在数据库内授权修改或新建独立测试实例。

知识目录不存在：IDEA Working directory或SMARTSERVE_DATA_PATH不正确。图片404：检查data/images和只读路径。中文乱码：项目和Maven使用UTF-8，终端日志编码显示不影响接口JSON。

重复启动：停止当前实例后再运行。浏览器窗口关掉不会释放8080或5173。改Java代码后旧JAR仍在运行，必须重新构建并重启。
