# 企业级改造 · 10 知识层(实体/消歧/关系/多跳, 批次 E)

> 日期: 2026-08-22 · 对应实施规范 E

## 数据模型(Flyway V5)
- `ai_entity`(类型/规范化名称唯一/归一化/状态/置信度) + `ai_entity_alias`(别名唯一/类型/来源/置信度)
- `ai_entity_mention`(原文提及→文档/版本/片段, 可追溯) + `ai_entity_merge_audit`(合并/拆分审计, 可回滚)
- `ai_relation`(SPO + validFrom/To + authority + confidence + status) + `ai_relation_evidence`(关系→证据片段)
- 跨租户绝不合并: 全部唯一约束含 tenant_id

## 核心服务 KnowledgeGraphService
- **消歧注册**: 别名精确匹配 → 归一化(小写去空格)匹配 → 规范化名称匹配 → 新建(自身=别名)
  - 低置信不自动合并: 仅精确/等价消歧, 歧义走人工 merge(可审计)
- **SPO 关系幂等**: 同 主体+谓词+客体/值 且 ACTIVE 时跳过; 支持时间范围/权威
- **合并**: 别名/关系转移 + 源实体置 MERGED(保留行供审计回滚)
- **图遍历(BFS 1~2 hop)**: 每跳返回 主体名/谓词/客体名·值/有效期(逐跳证据)

## 验收示例对照
- "小张/张三/张工"→ addAlias 后 resolve 任一 → 同一实体 ✅
- "小张的上级的上级"→ createRelation(小张→REPORTS_TO→李经理)+ (李经理→王总) → traverse("小张",REPORTS_TO,2) 出 2-hop 路径 ✅
- "去年换过几个领导"→ relation.validFrom/validTo 承载时间, 遍历结果按时间过滤(调用方) ✅

## 管理接口 /knowledge/graph/{entity-resolve,alias,relation,merge,traverse}(权限 knowledge:graph:*)

## 下一阶段(批次 F 及后续)
- LLM 实体/关系抽取(ingestion 异步 task, 结构化校验+幂等) → 填充 mention/evidence
- 冲突检测 SPO 化(同 subject+predicate 不同 object 即冲突, 而非仅文本相似)
- GraphRepository 抽象 + Neo4j adapter(数据稳定后)

## 验证
- knowledge 编译通过
- ⚠️ 图遍历/消歧端到端受沙箱限制未实测, 部署后按验收示例验证(建实体→建关系→traverse)

## 回滚
- git revert; 表 DROP(见 V5 注释); 图数据与检索链路解耦, 不影响主检索
