-- D1/D2: 企业级资源 ACL 与业务 Scope(批次 D, Flyway V4)
-- 由迁移执行方(yudao-server)启动时自动执行
-- 回滚: DROP TABLE ai_resource_acl / ai_knowledge_scope(见 docs/enterprise-upgrade/08-acl-scope.md)

-- ① 统一资源 ACL(资源级: 知识库/文档/片段/实体; DENY 优先于 ALLOW; 显式 ACL 优先于 visible_roles 兼容)
CREATE TABLE IF NOT EXISTS `ai_resource_acl` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `resource_type` varchar(32) NOT NULL COMMENT '资源类型: KB/DOCUMENT/CHUNK/ENTITY',
  `resource_id` bigint NOT NULL COMMENT '资源编号',
  `subject_type` varchar(16) NOT NULL COMMENT '主体类型: USER/ROLE/DEPT/ORG/ALL',
  `subject_id` varchar(64) NULL DEFAULT NULL COMMENT '主体编号(ALL 时为空)',
  `action` varchar(16) NOT NULL DEFAULT 'READ' COMMENT '动作: READ/WRITE/REVIEW/PUBLISH/ADMIN',
  `effect` varchar(8) NOT NULL DEFAULT 'ALLOW' COMMENT '效果: ALLOW/DENY',
  `inherit` bit(1) NOT NULL DEFAULT b'1' COMMENT '是否继承父资源(文档继承知识库)',
  `effective_from` datetime NULL DEFAULT NULL COMMENT '生效起始(空=永久)',
  `effective_to` datetime NULL DEFAULT NULL COMMENT '生效截止(空=永久)',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_resource_subject_action` (`tenant_id`, `resource_type`, `resource_id`, `subject_type`, `subject_id`, `action`, `effect`),
  INDEX `idx_resource` (`tenant_id`, `resource_type`, `resource_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '企业级资源 ACL';

-- ② 知识业务范围(省市/产品/渠道/客户分段: 检索硬过滤的一等模型)
CREATE TABLE IF NOT EXISTS `ai_knowledge_scope` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',
  `kb_id` bigint NOT NULL COMMENT '知识库编号',
  `scope_type` varchar(32) NOT NULL COMMENT '范围类型: PROVINCE/CITY/PRODUCT/CHANNEL/CUSTOMER_SEGMENT',
  `scope_code` varchar(64) NOT NULL COMMENT '范围编码(如 110000/北京/套餐A)',
  `scope_priority` int NOT NULL DEFAULT 0 COMMENT '优先级(精确城市>省级>全国; 小者优先)',
  `effective_from` datetime NULL DEFAULT NULL COMMENT '生效起始',
  `effective_to` datetime NULL DEFAULT NULL COMMENT '生效截止',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_kb_type_code` (`tenant_id`, `kb_id`, `scope_type`, `scope_code`),
  INDEX `idx_type_code` (`tenant_id`, `scope_type`, `scope_code`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '知识业务范围';
