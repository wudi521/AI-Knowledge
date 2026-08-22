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

## 后续
- B3 检索扩展闭环(子块命中带父块上下文) 待实施
- C 批: chunk 的 index_status/embedding 版本字段 + 向量持久化策略(C4)
