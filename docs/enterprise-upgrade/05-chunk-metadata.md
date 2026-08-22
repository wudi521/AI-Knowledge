# 企业级改造 · 05 ai_chunk 元数据与落库改造(批次 B1/B2)

> 日期: 2026-08-22 · 对应实施规范 B1/B2

## B1: ai_chunk 数据模型
- 新增字段: chunk_key(版本内唯一业务键)/chunk_seq(顺序)/chunk_role(PARENT|CHILD|LEAF|TABLE|IMAGE)/
  section_path(标题链)/source_page_start·end(页码)/token_count/content_hash(SHA-256)
- 唯一约束 `(tenant_id, version_id, chunk_key)` + 索引 `(tenant_id, version_id, status)`/`(tenant_id, parent_id)`
- Flyway V2 迁移(`yudao-server/db/migration/V2__ai_chunk_metadata.sql`): 加列 + 存量回填(legacy-{id}, 与 c%06d 前缀不冲突) + 约束/索引
- 回滚: DROP 唯一约束/索引/列(逐项, 见脚本注释)

## B2: Parent-Child 正确落库 + 元数据生成
- 切分器标注: StructureSplitter→LEAF+sectionPath+页码; ParentChildSplitter→PARENT/CHILD;
  TableSplitter→TABLE; 未标注由落库按 chunkType 推导
- persistChunks 两阶段批量插入: 阶段1 批量插父块/叶子(记录 下标→DB id) → 阶段2 批量插子块回填真实 parent_id
  (禁止逐条 insert; 跨版本不串——parent_id 只回填同版本内父块)
- chunkKey=c%06d(版本内唯一), contentHash=sha256, tokenCount=估算
- 冒烟 10 项 ALL PASSED(章节路径/页码/角色/chunkKey/seq/hash/token/TABLE推导)

## B3: 检索父子扩展闭环
- IngestionApi 新增 `getChunkParents(chunkId→parentId)` RPC
- SearchService: 候选过滤后批量取父块映射 → 去重取父块内容(单块截断300字, 总预算1000字)
  → ResultVO 新增 contextChunkId/contextContent(引用仍锚定命中子块, 父块仅上下文)
- 失败降级: RPC 异常返回空, 不阻断检索
- 编译通过(ingestion+retrieval)

## 批次 B 完成
- B1/B2/B3 全部交付; 下一批 C(Outbox/IngestionJob/IndexJob/两阶段发布)
