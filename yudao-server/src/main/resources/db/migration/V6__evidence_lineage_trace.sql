-- F1/F5: Evidence Lineage 与检索追踪(批次 F, Flyway V6)
-- 由迁移执行方(yudao-server)启动时自动执行
-- 回滚: DROP TABLE ai_answer_claim/ai_answer_citation/ai_retrieval_trace(见 docs/enterprise-upgrade/11-evidence-lineage.md)

-- ① 回答断言(claim → 证据片段, 逐条可追溯)
CREATE TABLE IF NOT EXISTS `ai_answer_claim` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `trace_id` varchar(64) NOT NULL COMMENT '评估链路追踪号',
  `claim_text` varchar(1024) NOT NULL COMMENT '断言原文',
  `verdict` varchar(16) NOT NULL COMMENT '判定: SUPPORTED/UNSUPPORTED',
  `evidence_chunk_id` bigint NULL DEFAULT NULL COMMENT '支撑证据片段(-1/空=无支撑)',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  INDEX `idx_trace` (`tenant_id`, `trace_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '回答断言(claim)';

-- ② 回答引用(一次回答的引用汇总, 锚定精确片段)
CREATE TABLE IF NOT EXISTS `ai_answer_citation` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `trace_id` varchar(64) NOT NULL COMMENT '评估链路追踪号',
  `query` varchar(500) NULL DEFAULT NULL COMMENT '问题',
  `answer_hash` varchar(64) NULL DEFAULT NULL COMMENT '回答SHA-256',
  `citation_chunk_ids` json NULL COMMENT '引用片段编号列表(SUPPORTED claim 的证据)',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_trace` (`tenant_id`, `trace_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '回答引用';

-- ③ 检索追踪(审计/评测: 路由/意图/通道统计/耗时)
CREATE TABLE IF NOT EXISTS `ai_retrieval_trace` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `trace_id` varchar(64) NOT NULL COMMENT '链路追踪号',
  `query` varchar(500) NOT NULL COMMENT '查询',
  `route` varchar(32) NULL DEFAULT NULL COMMENT '路由: HYBRID_RAG/SCOPE_FILTER_HYBRID_RAG/ABSTAIN',
  `intent` varchar(64) NULL DEFAULT NULL COMMENT '意图',
  `variant_count` int NOT NULL DEFAULT 0 COMMENT '检索变体数',
  `bm25_hits` int NOT NULL DEFAULT 0 COMMENT 'BM25 命中数',
  `vector_hits` int NOT NULL DEFAULT 0 COMMENT '向量命中数',
  `fused` int NOT NULL DEFAULT 0 COMMENT '融合候选数',
  `result_count` int NOT NULL DEFAULT 0 COMMENT '返回结果数',
  `elapsed_ms` int NOT NULL DEFAULT 0 COMMENT '耗时',
  `blocked` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否阻断(超范围/权限/范围不符)',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  INDEX `idx_trace` (`tenant_id`, `trace_id`),
  INDEX `idx_create` (`tenant_id`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '检索追踪';
