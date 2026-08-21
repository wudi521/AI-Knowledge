-- 切分策略文档级化(2026-08-21; ALTER 部分仅执行一次, 回填部分幂等)
-- 1) ai_document 增加文档级切分策略列(chunk_strategy + 参数 JSON)
-- 2) 存量文档按原知识库策略回填(先回填再删列, 顺序执行)
-- 3) ai_knowledge_base 删除切分策略与 Embedding 模型列(模型/策略均全局 DB 化)

-- ① ai_document 加列(仅执行一次)
ALTER TABLE `ai_document`
  ADD COLUMN `chunk_strategy` varchar(32) NOT NULL DEFAULT 'auto' COMMENT '切分策略: auto/structure/parent-child/semantic/policy/faq/table/image' AFTER `file_hash`,
  ADD COLUMN `chunk_strategy_params` varchar(1024) NULL DEFAULT NULL COMMENT '切分策略参数(JSON, 覆盖默认; 如 {"maxTokens":500,"overlap":1})' AFTER `chunk_strategy`;

-- ② 存量回填: 文档继承原知识库切分策略(回填后再删列; 幂等: 仅回填仍为默认 auto 且原库非 auto 的行)
UPDATE `ai_document` d
  LEFT JOIN `ai_knowledge_base` k ON d.kb_id = k.id
SET d.chunk_strategy = IFNULL(k.chunk_strategy, 'auto')
WHERE d.chunk_strategy = 'auto' AND k.chunk_strategy IS NOT NULL AND k.chunk_strategy <> 'auto';

-- ③ ai_knowledge_base 删列(仅执行一次; 若执行过报 Unknown column 说明已删除, 忽略)
ALTER TABLE `ai_knowledge_base`
  DROP COLUMN `chunk_strategy`,
  DROP COLUMN `embed_model`;
