# 企业级改造 · 00 基线记录(批次 A 起点)

> 日期: 2026-08-22 · 用途: 实施规范(AI_Knowledge_Enterprise_DeepSeek_Implementation_Prompt.md)要求逐项核实的代码现状, 作为改造基线

## 1. 已核实的关键代码现状

| 文件 | 现状(核实结果) | 问题 |
|---|---|---|
| `ParentChildSplitter` | 子块 parentId 为父块在切分结果列表中的下标 | **已修复**(`056c090` 落库回填真实 DB id), 检索侧父子扩展未做(B 批) |
| `IngestServiceImpl` | Kafka 异步消费; 单文档内 下载→解析→切分→全量 embedding→逐条插 MySQL | 无分批/断点/任务状态(C 批) |
| `IngestionApiImpl.indexVersion` | 发布时同步写 ES/Milvus + 置 PUBLISHED; **幂等覆盖式** | 无两阶段索引/STAGING generation(C 批) |
| `AiDocVersionServiceImpl.publish` | **@Transactional 事务内 Feign 调 indexVersion(远程写)** | 违反"事务内禁远程副作用"原则(C 批) |
| `AiDocVersionServiceImpl.expireOldVersions` | 只改 ai_doc_version 状态 | **已修复**(`056c090` 级联 deleteVersionIndex) |
| `EsChunkStore` / `MilvusChunkStore` | 无 version_id/document_id/有效期/父子角色等过滤字段 | B/C 批加字段 |
| `SearchService` | 查询分析→BM25+向量→RRF→已发布过滤→重排; **无 Parent Expansion** | B 批检索扩展 |
| `ResultFilter` | filterPublished 按 chunk 状态(PUBLISHED)过滤 | 依赖 chunk 状态正确性(已由失效链修复兜底) |
| `SecurityConfiguration`(10 个 AI 模块) | `/actuator/**`、`/druid/**` permitAll; `PREFIX/**` permitAll 供 Feign | **已修复**(A1 收紧 actuator/druid) |
| `TokenAuthenticationFilter`(framework) | **无条件信任 login-user 头**(任何直连可伪造用户) | **已修复**(A1 内部签名校验) |
| `LoginUserRequestInterceptor`(framework) | Feign 透传 login-user, 无来源认证 | **已修复**(A1 出站签名) |
| 网关 `TokenAuthenticationFilter` | 登录后设置 login-user 头; 已剥离外部 login-user 头(第85行) | **已增强**(A1 设置头时同步签名) |
| `AiModelConfigDO` | apiKey 明文字段(仅响应脱敏) | A 批(A2) 加密 |
| `IngestServiceImpl.downloadFromMinio` | hutool HttpUtil.downloadFile 直下任意 URL | A 批(A3) SSRF 防护 |
| SQL 体系 | `sql/` 手写脚本 + 各模块 `resources/sql/`, 无 Flyway | A 批(A4) |
| `ai_chunk.embedding` | JSON 文本存高维向量 | C 批(C4) 迁移 |

## 2. 关键安全链路(login-user 流向)

```
用户 → 网关(剥离外部 login-user → 登录后设置真实 login-user + 内部签名) → 业务服务(TokenAuthenticationFilter 校验签名后信任)
业务服务 → Feign(LoginUserRequestInterceptor 透传 login-user + 内部签名) → 业务服务(同上校验)
外部直连业务服务(伪造 login-user, 无签名) → TokenAuthenticationFilter 签名校验失败 → 剥离, 按匿名处理
```

## 3. 批次 A 完成情况

- [x] A1 内部 RPC 认证与 login-user 防伪造(`01-internal-auth.md`)
- [x] A2 模型密钥加密(`02-model-secret.md`)
- [x] A3 文件下载与上传安全(`03-download-security.md`)
- [x] A4 Flyway 版本化迁移(`04-flyway.md`) — **批次 A 完成**
