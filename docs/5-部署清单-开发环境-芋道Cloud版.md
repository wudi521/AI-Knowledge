# 部署清单:开发环境(芋道 yudao-cloud-mini · JDK17)

> 版本:v1.0 · 2026-08-15
> 适用代码:`/Users/wudi/IdealProjects/myself/yudao-cloud-mini-master-jdk17`
> 本清单为芋道 Cloud 精简版**独立新建**,与《5-部署清单-开发环境.md》(此前架构)互不影响。
> 配套文件:`deploy/yudao-cloud-dev/docker-compose.yml`、`deploy/yudao-cloud-dev/init-dev.sh`

## 1. 代码与版本确认(已核实)

| 项 | 值 |
|----|----|
| 项目 | 芋道 yudao-cloud-mini(微服务精简版) |
| 分支 | master-jdk17 |
| JDK | **17**(根 pom `java.version=17`) |
| Spring Boot | 3.5.x(bom 3.5.15) |
| Spring Cloud / Alibaba | 2025.0.1 / 2025.0.0.0 |
| 启用模块 | yudao-gateway、yudao-module-system、yudao-module-infra、yudao-server(聚合单体)、yudao-framework |
| 预留模块(注释中,按需启用) | bpm / pay / mp / mall / crm / erp / iot / mes / **ai** / wms / im |
| AI 能力 | yudao-module-ai 预留 + `spring.ai` 配置(向量存储支持 Redis / Qdrant(6334) / Milvus(19530),模型支持 OpenAI / 通义 / DeepSeek / Ollama,含 MCP) |
| 文档 | 芋道文档站 https://doc.iocoder.cn(文字文档免费,视频教程部分付费) |

## 2. 两种运行模式(二选一,端口区分)

| 模式 | 启动入口 | 端口 | 说明 |
|------|---------|------|------|
| **微服务模式(推荐)** | yudao-gateway(GatewayServerApplication) | 网关 48080 | system-server:48081、infra-server:48082,各模块独立进程,经网关路由 `/admin-api/system/**`、`/admin-api/infra/**` |
| 单体聚合模式 | yudao-server(YudaoServerApplication) | 48080 | 聚合 system + infra 依赖,单进程运行,适合调试(与网关二选一,端口相同勿同启) |

本清单以**微服务模式**为主线,单体模式作为调试备选。

## 3. 架构拓扑

```
┌────────── Docker Desktop(中间件) ──────────┐
│  MySQL 8  :3306  库 ruoyi-vue-pro(必需)    │
│  Redis 7  :6379(必需)                       │
│  Nacos 2  :8848/9848 namespace=dev(必需)    │
│  MinIO    :9000/9001(推荐,文件存储)          │
│  ES / Kafka / Milvus(可选,AI 链路用)         │
└─────────────────────────────────────────────┘
        │ Nacos 注册/配置
┌───────▼─────────────────────────────────────┐
│  IDEA 启动(JDK17)                            │
│  gateway-server :48080(路由 /admin-api/**)   │
│  system-server  :48081(用户/角色/菜单/租户)   │
│  infra-server   :48082(文件/定时任务/日志)    │
│  (AI 独立服务:检索/证据/入库 worker,后续新增) │
└─────────────────────────────────────────────┘
        │ /admin-api → 48080
┌───────▼─────────────────────────────────────┐
│  yudao-ui-admin-vue3(独立仓库,npm run dev)    │
│  登录 admin / admin123                        │
└─────────────────────────────────────────────┘
```

## 4. 前置条件检查清单

| 检查项 | 要求 | 说明 |
|--------|------|------|
| JDK | **17**(本分支是 jdk17,不是 21) | `java -version` 确认 |
| Maven | 3.8+ | `mvn -version` |
| Docker Desktop | 已启动,内存 ≥ 8GB | MySQL+Nacos+Redis 常驻,可选组件较多 |
| Node.js | 18 LTS / 20 LTS | 前端(yudao-ui-admin-vue3 独立仓库) |
| 代码就绪 | 仓库已下载,IDEA 导入根 pom | 等待 Maven 依赖下载(首次较久) |
| IDEA 插件 | Lombok | 芋道必装 |

## 5. 中间件部署(Docker Compose)

### 5.1 使用文件与清单

编排文件:`deploy/yudao-cloud-dev/docker-compose.yml`

| 服务 | 镜像(arm64 适配) | 端口 | 必要性 | 账号 |
|------|------|------|--------|------|
| mysql | mysql:8.0 | 3306 | **必需** | root / 123456(local 配置默认) |
| redis | redis:7.0-alpine | 6379 | **必需** | 无 |
| nacos | nacos/nacos-server:v2.4.3 | 8848/9848 | **必需** | nacos / nacos,namespace=dev |
| minio | minio/minio:latest | 9000/9001 | 推荐 | minioadmin / minioadmin123 |
| elasticsearch | docker.elastic.co/elasticsearch/elasticsearch:8.13.4 | 9200 | 可选(AI 检索) | 关安全 |
| kafka | apache/kafka:3.9.0 | 9092 | 可选(AI 异步) | PLAINTEXT |
| milvus | milvusdb/milvus:v2.4.13 | 19530/9091 | 可选(AI 向量) | 无鉴权(内置 etcd+minio) |

> arm64(Apple Silicon)说明:nacos 2.3.x、Docker Hub 版 elasticsearch、apache/kafka 3.7.1 **均无 arm64 镜像**,已替换为支持 arm64 的版本;minio 使用 latest。已在本机实测 **9/9 healthy**。

> 首次把系统跑通,只需 `mysql + redis + nacos` 三个;MinIO 建议一并起;其余按 AI 链路需要再开(compose 中已写好,取消注释即可)。

### 5.2 启动与健康检查

```bash
cd deploy/yudao-cloud-dev
docker compose up -d                          # 启动(必需三件 + minio)
docker compose ps                              # 看 health 状态

# 健康检查
docker compose exec mysql mysqladmin ping -uroot -p123456
docker compose exec redis redis-cli ping
curl -s http://localhost:8848/nacos/v1/console/health/readiness   # ok
curl -s http://localhost:9000/minio/health/live
```

## 6. 初始化(一次性)

执行 `deploy/yudao-cloud-dev/init-dev.sh`,自动完成:

1. 等待 MySQL / Redis / Nacos 就绪
2. **导入芋道 SQL**(需指定仓库 sql 目录,默认已填你的路径):
   `sql/mysql/ruoyi-vue-pro.sql`(业务表)→ `sql/mysql/quartz.sql`(定时任务表)
3. **创建 Nacos 命名空间 `dev`**(幂等,框架所有服务都注册在 dev)
4. 可选:创建 MinIO 桶、Kafka 主题

```bash
cd deploy/yudao-cloud-dev
# 若仓库路径不同,先改脚本顶部 REPO_SQL_DIR 变量
bash init-dev.sh
```

手动等价命令(不想用脚本时):

```bash
# 1. 导入 SQL(表结构,先业务后 quartz)
docker compose exec -T mysql sh -c 'exec mysql -uroot -p123456' < /Users/wudi/IdealProjects/myself/yudao-cloud-mini-master-jdk17/sql/mysql/ruoyi-vue-pro.sql
docker compose exec -T mysql sh -c 'exec mysql -uroot -p123456' < /Users/wudi/IdealProjects/myself/yudao-cloud-mini-master-jdk17/sql/mysql/quartz.sql

# 2. Nacos 创建命名空间 dev(名称随意,id 固定 dev)
curl -X POST 'http://127.0.0.1:8848/nacos/v1/console/namespaces' \
  -d 'customNamespaceId=dev&namespaceName=dev&namespaceDesc=开发环境'
```

> 说明:芋道 local 配置默认连 `ruoyi-vue-pro` 库、`root/123456`、Nacos `dev` 命名空间。若你改了数据库密码或命名空间,需同步改 `application-local.yaml`。

## 7. 启动芋道服务(IDEA)

### 7.1 微服务模式(推荐)

按顺序在 IDEA 启动 3 个主类(可配 Compound Run Configuration):

| 顺序 | 主类 | 服务 | 端口 | 作用 |
|------|------|------|------|------|
| 1 | `cn.iocoder.yudao.gateway.GatewayServerApplication` | gateway-server | 48080 | 网关/路由 |
| 2 | `cn.iocoder.yudao.module.system...SystemServerApplication` | system-server | 48081 | 用户/角色/菜单/租户/认证 |
| 3 | `cn.iocoder.yudao.module.infra...InfraServerApplication` | infra-server | 48082 | 文件/代码生成/定时任务/日志 |

启动参数(可选,链路追踪用 SkyWalking 时加 `-javaagent`),日常无需额外参数。
验证注册:浏览器打开 `http://127.0.0.1:8848/nacos`,服务管理 → 服务列表,应看到 `gateway-server`、`system-server`、`infra-server`(命名空间 dev)。

### 7.2 单体模式(调试备选)

只启动 `cn.iocoder.yudao.server.YudaoServerApplication`(端口 48080),聚合 system + infra。注意与网关端口冲突,二选一。

## 8. 前端启动(独立仓库)

芋道 Cloud 前端是独立仓库,不在本次代码内:

```bash
git clone https://gitee.com/zhijiantianya/yudao-ui-admin-vue3.git   # 或你已有副本
cd yudao-ui-admin-vue3
npm install
npm run dev
```

Vite 代理已内置:请求 `/admin-api/**` 转发到 `http://127.0.0.1:48080`(按仓库 vite.config 实际端口,通常 80 或 5173)。

浏览器访问前端地址,登录:**admin / admin123**(租户默认 1)。

## 9. 验证(冒烟)

```bash
# 1. 三个服务健康
curl -s http://127.0.0.1:48080/actuator/health        # 网关(UP)
curl -s http://127.0.0.1:48081/actuator/health        # system
curl -s http://127.0.0.1:48082/actuator/health        # infra

# 2. 登录拿 token(admin/admin123)
curl -s -X POST http://127.0.0.1:48080/admin-api/system/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123","tenantId":1}'

# 3. 拿用户信息(带上面返回的 accessToken)
curl -s http://127.0.0.1:48080/admin-api/system/auth/get-permission-info \
  -H "Authorization: Bearer <accessToken>"

# 4. 前端页面正常登录、能看到菜单 = 框架跑通
```

## 10. AI 链路衔接(下一步)

芋道自带 `spring.ai` 配置(yudao-server/application.yaml 中),与本项目 AI 客服链路的关系:

| 能力 | 芋道侧 | 本项目 AI 服务 |
|------|--------|----------------|
| 向量存储 | spring.ai.vectorstore 三选一:Redis / Qdrant(6334)/ **Milvus(19530)** | 我们的检索服务使用 Milvus,建议统一走 Milvus |
| 模型 | OpenAI / 通义 / DeepSeek / Ollama(配置 key 即可) | 我们的 model-gateway 独立管理路由/降级/计量 |
| 知识问答(RAG) | 芋道 AI 模块(yudao-module-ai,注释中,可启用) | 我们的检索+证据+入库管线独立实现,不依赖芋道 AI |
| 异步 | Kafka(9092) | ingestion-worker 消费 knowledge-ingest |

衔接方式:芋道负责**管理底座与认证**(登录、RBAC、多租户、菜单),我们的 AI 服务注册进同一 Nacos,在网关加路由(`/admin-api/ai/** → chat-server`),前端在芋道菜单里挂 AI 工作台页面。这一步等框架跑通后我们再接。

## 11. 常见问题

| 现象 | 原因 | 处理 |
|------|------|------|
| 服务启动报 Nacos 连接失败 | Nacos 未起或命名空间未建 | 起 nacos;init 脚本建 namespace `dev`;确认 local 配置 server-addr |
| 启动报数据库连接失败 | 库/表未导入或密码不对 | 确认导入 ruoyi-vue-pro.sql + quartz.sql;local 配置 root/123456 |
| 48080 端口冲突 | 网关与 yudao-server 同启 | 二选一(微服务模式只启网关) |
| 前端登录失败 | 代理端口不对 / 网关未起 | 确认前端代理指向 48080;网关 healthy |
| 依赖下载慢 | Maven 首次 | 配置阿里云镜像;耐心等待 |
| 启用 AI 模块报错 | yudao-module-ai 需额外中间件 | 按需先启 Milvus/Qdrant 或 Redis 向量,再开模块 |

## 12. 附录

### 12.1 端口总表

| 端口 | 服务 |
|------|------|
| 3306 | MySQL |
| 6379 | Redis |
| 8848 / 9848 | Nacos |
| 9000 / 9001 | MinIO |
| 48080 | gateway-server(或 yudao-server 单体) |
| 48081 | system-server |
| 48082 | infra-server |
| 9200 / 9092 / 19530 | ES / Kafka / Milvus(可选) |

### 12.2 账号速查

| 资源 | 账号/密码 |
|------|----------|
| MySQL | root / 123456(库 ruoyi-vue-pro) |
| Nacos | nacos / nacos(命名空间 dev) |
| MinIO | minioadmin / minioadmin123(桶 kb-docs,AI 链路用) |
| 芋道登录 | admin / admin123(租户 1) |

### 12.3 常用命令

```bash
cd deploy/yudao-cloud-dev
docker compose up -d          # 起中间件
docker compose ps             # 状态
docker compose logs -f nacos  # 看日志
bash init-dev.sh              # 初始化(幂等,可重跑)
docker compose down           # 停止(保留数据)
docker compose down -v        # 停止并清空数据
```
