# 改造方案:AI 客服服务接入芋道 Cloud(多模块拆分版)

> 版本:v1.1 · 2026-08-15
> 决策更新:服务**不收敛到单一模块**,按旧清单服务一一拆分为独立 `yudao-module-xxx`(api + server),符合芋道云版"一业务一模块"规范。
> 原则:不动芋道原有模块(gateway/system/infra);新增模块全部为**新增代码**,零侵入。

---

## 1. 模块拆分(旧清单 12 服务 → 11 个芋道模块)

| 旧清单服务 | 芋道模块(新建) | 服务名 | 端口 | 内容 |
|-----------|---------------|--------|------|------|
| gateway-service | yudao-gateway(复用) | gateway-server | 48080 | 新增全部 `/admin-api/ai-*/**` 路由 |
| chat-service | yudao-module-chat | chat-server | 48083 | 对话工作台、SSE 流式 |
| knowledge-service | yudao-module-knowledge | knowledge-server | 48084 | 知识库/文档/版本/审核 |
| ingestion-worker | yudao-module-ingestion | ingestion-server | 48085 | Kafka 入库消费者(解析→切分→向量化→索引) |
| retrieval-service | yudao-module-retrieval | retrieval-server | 48086 | 语义理解/改写/拆解/混合检索/重排 |
| evidence-service | yudao-module-evidence | evidence-server | 48087 | 证据组装/冲突检测/Claim 验证 |
| rule-engine-service | yudao-module-rule | rule-server | 48088 | Drools 硬规则 |
| agent-service | yudao-module-agent | agent-server | 48089 | 工具注册表、调用审批 |
| workflow-service | yudao-module-workflow | workflow-server | 48090 | 流程执行(或启用芋道 bpm 替代) |
| model-gateway | yudao-module-model | model-server | 48091 | 模型路由/降级/计量 |
| eval-platform | yudao-module-eval | eval-server | 48092 | 评测任务/指标/上线闸门 |
| governance-service | yudao-module-governance | governance-server | 48093 | 成本分摊/审计查询/越权报表 |
| admin-console | yudao-ui-admin-vue3(独立仓库) | - | 5173 | 前端菜单挂 AI 页面 |

每个模块 = `yudao-module-xxx`(父 pom)+ `yudao-module-xxx-api`(Feign 接口/枚举/DTO)+ `yudao-module-xxx-server`(实现,独立启动)。

## 2. 模块依赖与调用关系(Feign)

```
                  ┌────────────── yudao-gateway (48080) ──────────────┐
                  │ 路由: /admin-api/chat/**  knowledge/**  retrieval/** ... │
                  └──────────────────────┬────────────────────────────┘
        ┌──────────────┬─────────────────┼──────────────────┬─────────────┐
        ▼              ▼                 ▼                  ▼             ▼
  chat-server   knowledge-server   retrieval-server   evidence-server  model-server
  (48083)         (48084)            (48086)            (48087)        (48091)
     │ 调 retrieval/evidence/model    │ 调 evidence      │               │
     │                               ▼                  │               │
     └──────────────►  eval-server (48092) ◄─────────────┘               │
                     agent-server (48089) → workflow-server (48090)      │
                     rule-server (48088) ← chat/retrieval 调用            │
                     ingestion-server (48085):消费 Kafka,写 knowledge,   │
                       调 model(Embedding)、调 infra(文件)                │
                     governance-server (48093):读全部模块日志/计量         │
   所有模块 ──Feign──► system-server(认证/用户/租户) + infra-server(文件/审计)
```

- 模块间全部走 Feign(api 模块定义接口,server 实现)
- 同步链路(实时问答):chat → retrieval → evidence → model,串行调用
- 异步链路(入库):knowledge 发 Kafka → ingestion 消费 → 回调 knowledge 状态
- 权限/租户/审计:复用 system/infra,业务模块不重复实现

## 3. 每个模块的标准结构(芋道规范)

```
yudao-module-xxx/
├── pom.xml                                        # 父 pom(聚合 api + server)
├── yudao-module-xxx-api/
│   └── src/main/java/cn/iocoder/yudao/module/xxx/
│       ├── enums/ApiConstants.java                # Feign 服务名/包名
│       ├── enums/ErrorCodeConstants.java          # 错误码
│       └── api/ XxxApi.java + dto/                # 对外 Feign 接口
└── yudao-module-xxx-server/
    └── src/main/
        ├── java/cn/iocoder/yudao/module/xxx/
        │   ├── XxxServerApplication.java
        │   ├── controller/admin/...               # Controller + vo/
        │   ├── service/...                        # Service + Impl
        │   ├── dal/dataobject/...                 # DO(extends BaseDO)
        │   ├── dal/mysql/...                      # Mapper(extends BaseMapperX)
        │   ├── mq/ (consumer/producer/message)    # Kafka(ingestion 等)
        │   ├── framework/security/config/SecurityConfiguration.java
        │   ├── framework/rpc/config/RpcConfiguration.java   # Feign 客户端
        │   └── api/XxxApiImpl.java                # Feign 实现
        └── resources/
            ├── application.yaml / application-local.yaml / application-dev.yaml
            └── sql/xxx.sql                        # 本模块表 DDL
```

## 4. 数据库(AI 业务表按模块归属,统一前缀)

| 模块 | 表 |
|------|----|
| knowledge | ai_knowledge_base、ai_document、ai_doc_version、ai_chunk |
| chat | ai_conversation、ai_message、ai_feedback |
| evidence | ai_evidence |
| eval | ai_eval_task、ai_eval_case |
| agent | ai_tool_registry、ai_tool_call |
| governance | ai_cost_record、ai_audit_ext(越权拦截) |
| model | 不建表(计量写 governance 或 Redis) |

向量存 Milvus,MySQL 只存文本与元数据(与《技术方案》5.3 一致)。

## 5. 落地步骤

1. 中间件就绪(MySQL/Redis/Nacos/MinIO,可选 ES/Kafka/Milvus)
2. 执行各模块 `sql/` 下的 DDL(或统一脚本)
3. IDEA 导入根 pom(已注册全部模块),等待依赖
4. 启动顺序:gateway(48080)→ system(48081)→ infra(48082)→ **11 个新服务(48083~48093)**
5. Nacos(namespace=dev)确认全部注册
6. 网关路由已配置(见 6.3);前端菜单挂 AI 页面
7. 冒烟:`/admin-api/knowledge/knowledge-base/page` 返回列表

## 6. 交付内容

### 6.1 已生成的模块骨架(本次交付)

| 模块 | 骨架内容 |
|------|---------|
| 全部 11 个模块 | 父 pom + api 工程(pom/Feign 常量/错误码)+ server 工程(pom/启动类/application 三套配置/Security+Feign 配置/健康 Controller) |
| knowledge(完整样例) | 知识库 CRUD 全链路:DO/Mapper/Service/Impl/VO/Controller + DDL |
| chat(SSE 样例) | 对话接口骨架 + SSE 流式 Controller |
| retrieval / evidence / model / eval / agent / rule / workflow / ingestion / governance | 最小可启动骨架 + 领域包结构与接口签名注释 |

### 6.2 根 pom 注册(已改)

根 pom `<modules>` 新增 11 个模块(芋道原模块不动)。

### 6.3 网关路由(已加)

gateway `application.yaml` 新增路由段:`/admin-api/{chat,knowledge,retrieval,evidence,model,eval,agent,rule,workflow,ingestion,governance}/**` → 对应 `grayLb://xxx-server`。

## 7. 说明

- 所有新增模块均为**新增文件**,不改芋道既有代码;若想回退,删除模块目录并移除根 pom 对应行即可
- 骨架配置为"最小可启动",正式开发时以芋道 system 的配置为模板补齐细节(如 yudao.xss、验证码等)
- 需要 bpm 做退款流程时,启用芋道 yudao-module-bpm,workflow-server 通过 Feign 调用它
