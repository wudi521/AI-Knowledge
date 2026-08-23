-- 历史专利知识库领域修复
-- 背景：migrate-20260822-patent-mvp-v01.sql 新增 domain_code 时，历史知识库默认 GENERAL。
-- 若历史专利文档已经存在 domain_metadata(applicationNo/publicationNo)，审核链会被误判为 GENERAL，
-- 进而生成 POLICY/LEGAL/SOP 等客服式 ReviewItem。
--
-- 本迁移只处理“已有明确专利领域元数据”的知识库，不按知识库名称猜测，避免误伤。
-- 执行后，相关文档需要通过“文档管理 -> 重新处理”重新跑完整入库流程。

-- 1) 修正知识库领域：只要库内存在明确专利元数据的文档，就标记为 PATENT。
UPDATE ai_knowledge_base kb
JOIN (
    SELECT DISTINCT d.kb_id
    FROM ai_document d
    WHERE d.domain_metadata IS NOT NULL
      AND JSON_VALID(d.domain_metadata) = 1
      AND (
          NULLIF(JSON_UNQUOTE(JSON_EXTRACT(d.domain_metadata, '$.applicationNo')), '') IS NOT NULL
          OR NULLIF(JSON_UNQUOTE(JSON_EXTRACT(d.domain_metadata, '$.publicationNo')), '') IS NOT NULL
      )
) patent_kb ON patent_kb.kb_id = kb.id
SET kb.domain_code = 'PATENT'
WHERE COALESCE(kb.domain_code, 'GENERAL') <> 'PATENT';

-- 2) 清理历史误生成的客服式审核条目。
-- PATENT 领域采用文档级审核，不应存在 POLICY/PRICE/LEGAL/FAQ/SOP ReviewItem。
DELETE ri
FROM ai_review_item ri
JOIN ai_document d ON d.id = ri.doc_id
JOIN ai_knowledge_base kb ON kb.id = d.kb_id
WHERE kb.domain_code = 'PATENT'
  AND d.domain_metadata IS NOT NULL
  AND JSON_VALID(d.domain_metadata) = 1
  AND (
      NULLIF(JSON_UNQUOTE(JSON_EXTRACT(d.domain_metadata, '$.applicationNo')), '') IS NOT NULL
      OR NULLIF(JSON_UNQUOTE(JSON_EXTRACT(d.domain_metadata, '$.publicationNo')), '') IS NOT NULL
  );

-- 3) 验证结果：应看到专利库 domain_code=PATENT，且专利文档对应 ReviewItem 数为 0。
SELECT kb.id AS kb_id,
       kb.name AS kb_name,
       kb.domain_code,
       COUNT(DISTINCT d.id) AS patent_document_count,
       COUNT(ri.id) AS stale_review_item_count
FROM ai_knowledge_base kb
JOIN ai_document d ON d.kb_id = kb.id
LEFT JOIN ai_review_item ri ON ri.doc_id = d.id
WHERE d.domain_metadata IS NOT NULL
  AND JSON_VALID(d.domain_metadata) = 1
  AND (
      NULLIF(JSON_UNQUOTE(JSON_EXTRACT(d.domain_metadata, '$.applicationNo')), '') IS NOT NULL
      OR NULLIF(JSON_UNQUOTE(JSON_EXTRACT(d.domain_metadata, '$.publicationNo')), '') IS NOT NULL
  )
GROUP BY kb.id, kb.name, kb.domain_code;
