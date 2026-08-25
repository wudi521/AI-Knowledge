-- 专利业务标识符索引：精确申请号/公布号查询不再扫描知识库全部文档。
-- 目标数据库：MySQL 8.0。
-- 生成列从 domain_metadata 派生，历史数据无需单独回填；原始编号仍保留在 JSON 中用于展示。

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_document'
      AND COLUMN_NAME = 'patent_application_no_norm') = 0,
  'ALTER TABLE `ai_document` ADD COLUMN `patent_application_no_norm` varchar(64)
     GENERATED ALWAYS AS (
       CASE WHEN JSON_VALID(`domain_metadata`) = 1
         THEN UPPER(REPLACE(JSON_UNQUOTE(JSON_EXTRACT(`domain_metadata`, ''$.applicationNo'')), '' '', ''''))
         ELSE NULL END
     ) STORED COMMENT ''规范化专利申请号(索引列)''',
  'SELECT 1');
PREPARE s1 FROM @ddl; EXECUTE s1; DEALLOCATE PREPARE s1;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_document'
      AND COLUMN_NAME = 'patent_publication_no_norm') = 0,
  'ALTER TABLE `ai_document` ADD COLUMN `patent_publication_no_norm` varchar(64)
     GENERATED ALWAYS AS (
       CASE WHEN JSON_VALID(`domain_metadata`) = 1
         THEN UPPER(REPLACE(JSON_UNQUOTE(JSON_EXTRACT(`domain_metadata`, ''$.publicationNo'')), '' '', ''''))
         ELSE NULL END
     ) STORED COMMENT ''规范化专利公布号(索引列)''',
  'SELECT 1');
PREPARE s2 FROM @ddl; EXECUTE s2; DEALLOCATE PREPARE s2;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_document'
      AND INDEX_NAME = 'idx_kb_patent_application_no') = 0,
  'ALTER TABLE `ai_document` ADD INDEX `idx_kb_patent_application_no`
     (`tenant_id`, `kb_id`, `patent_application_no_norm`, `deleted`)',
  'SELECT 1');
PREPARE s3 FROM @ddl; EXECUTE s3; DEALLOCATE PREPARE s3;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_document'
      AND INDEX_NAME = 'idx_kb_patent_publication_no') = 0,
  'ALTER TABLE `ai_document` ADD INDEX `idx_kb_patent_publication_no`
     (`tenant_id`, `kb_id`, `patent_publication_no_norm`, `deleted`)',
  'SELECT 1');
PREPARE s4 FROM @ddl; EXECUTE s4; DEALLOCATE PREPARE s4;
