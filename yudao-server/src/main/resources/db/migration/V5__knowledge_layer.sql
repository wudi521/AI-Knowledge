-- E: 知识层一等模型(实体/别名/提及/关系/关系证据, 批次 E, Flyway V5)
-- 由迁移执行方(yudao-server)启动时自动执行
-- 回滚: DROP TABLE ai_relation_evidence/ai_relation/ai_entity_mention/ai_entity_alias/ai_entity/ai_entity_merge_audit
--        (见 docs/enterprise-upgrade/10-knowledge-layer.md)

-- ① 实体(规范化名称 + 状态 + 置信度; 跨租户绝不合并)
CREATE TABLE IF NOT EXISTS `ai_entity` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `kb_id` bigint NULL DEFAULT NULL COMMENT '来源知识库(可空=全局实体)',
  `entity_type` varchar(32) NOT NULL DEFAULT 'GENERIC' COMMENT '实体类型: PERSON/PRODUCT/ORG/POLICY/GENERIC',
  `canonical_name` varchar(128) NOT NULL COMMENT '规范化名称(唯一)',
  `normalized_name` varchar(128) NOT NULL COMMENT '归一化名称(小写去空格, 消歧用)',
  `attributes` json NULL COMMENT '扩展属性',
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE/MERGED/SPLIT/ARCHIVED',
  `confidence` decimal(5,4) NOT NULL DEFAULT 1.0000 COMMENT '置信度',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_canonical` (`tenant_id`, `canonical_name`),
  INDEX `idx_tenant_type` (`tenant_id`, `entity_type`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '知识实体';

-- ② 实体别名(消歧: 小张/张三/张工 → 同一实体)
CREATE TABLE IF NOT EXISTS `ai_entity_alias` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `entity_id` bigint NOT NULL COMMENT '实体编号',
  `alias` varchar(128) NOT NULL COMMENT '别名',
  `alias_type` varchar(16) NOT NULL DEFAULT 'SYNONYM' COMMENT '别名类型: SYNONYM/ABBREVIATION/NICKNAME',
  `confidence` decimal(5,4) NOT NULL DEFAULT 1.0000 COMMENT '置信度',
  `source` varchar(16) NOT NULL DEFAULT 'MANUAL' COMMENT '来源: MANUAL/LLM/RULE',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_alias` (`tenant_id`, `alias`),
  INDEX `idx_entity` (`tenant_id`, `entity_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '实体别名';

-- ③ 实体提及(原文位置, 可追溯)
CREATE TABLE IF NOT EXISTS `ai_entity_mention` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `entity_id` bigint NOT NULL COMMENT '实体编号',
  `document_id` bigint NOT NULL COMMENT '文档编号',
  `version_id` bigint NOT NULL COMMENT '版本编号',
  `chunk_id` bigint NOT NULL COMMENT '片段编号',
  `mention_text` varchar(256) NOT NULL COMMENT '原文提及',
  `source_locator` varchar(128) NULL DEFAULT NULL COMMENT '来源定位(页码/段落/偏移)',
  `source` varchar(16) NOT NULL DEFAULT 'LLM' COMMENT '来源: MANUAL/LLM/RULE',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  INDEX `idx_entity` (`tenant_id`, `entity_id`),
  INDEX `idx_chunk` (`tenant_id`, `chunk_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '实体提及';

-- ④ 关系(SPO + 时间范围 + 权威 + 置信度)
CREATE TABLE IF NOT EXISTS `ai_relation` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `subject_entity_id` bigint NOT NULL COMMENT '主体实体',
  `predicate` varchar(64) NOT NULL COMMENT '谓词(如 REPORTS_TO/REFUND_PERIOD)',
  `object_entity_id` bigint NULL DEFAULT NULL COMMENT '客体实体(值型关系为 NULL)',
  `object_value` varchar(256) NULL DEFAULT NULL COMMENT '客体值(属性型关系, 如 30天)',
  `valid_from` date NULL DEFAULT NULL COMMENT '有效期起始',
  `valid_to` date NULL DEFAULT NULL COMMENT '有效期截止',
  `authority` int NOT NULL DEFAULT 0 COMMENT '权威级别(高者优先)',
  `confidence` decimal(5,4) NOT NULL DEFAULT 0.9000 COMMENT '置信度',
  `source` varchar(16) NOT NULL DEFAULT 'LLM' COMMENT '来源: MANUAL/LLM/RULE',
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE/SUPERSEDED/ARCHIVED',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  INDEX `idx_subject_predicate` (`tenant_id`, `subject_entity_id`, `predicate`),
  INDEX `idx_object` (`tenant_id`, `object_entity_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '知识关系';

-- ⑤ 关系证据(relation → 文档版本/片段, 可追溯)
CREATE TABLE IF NOT EXISTS `ai_relation_evidence` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `relation_id` bigint NOT NULL COMMENT '关系编号',
  `document_id` bigint NOT NULL COMMENT '文档编号',
  `version_id` bigint NOT NULL COMMENT '版本编号',
  `chunk_id` bigint NOT NULL COMMENT '片段编号',
  `evidence_text` varchar(1024) NULL DEFAULT NULL COMMENT '证据原文(摘要)',
  `confidence` decimal(5,4) NOT NULL DEFAULT 0.9000 COMMENT '置信度',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  INDEX `idx_relation` (`tenant_id`, `relation_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '关系证据';

-- ⑥ 实体合并/拆分审计(可撤销/可审计)
CREATE TABLE IF NOT EXISTS `ai_entity_merge_audit` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `operation` varchar(16) NOT NULL COMMENT '操作: MERGE/SPLIT',
  `from_entity_id` bigint NOT NULL COMMENT '来源实体(合并时=被并入实体)',
  `to_entity_id` bigint NOT NULL COMMENT '目标实体(合并时=保留实体; 拆分时=新实体)',
  `reason` varchar(512) NULL DEFAULT NULL COMMENT '原因',
  `operator` varchar(64) NULL DEFAULT NULL COMMENT '操作人',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  INDEX `idx_entity` (`tenant_id`, `from_entity_id`),
  INDEX `idx_target` (`tenant_id`, `to_entity_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '实体合并/拆分审计';
