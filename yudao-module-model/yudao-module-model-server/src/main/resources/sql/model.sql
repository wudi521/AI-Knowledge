-- 模型配置表(模型网关: chat / embedding / rerank 统一配置)
CREATE TABLE IF NOT EXISTS `ai_model_config` (
  `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT '编号',
  `name`        varchar(64)  NOT NULL COMMENT '名称',
  `type`        varchar(16)  NOT NULL COMMENT '类型: chat / embedding / rerank',
  `provider`    varchar(32)  NOT NULL DEFAULT 'OLLAMA' COMMENT '供应商: OLLAMA / OPENAI / ALIYUN / XINFERENCE',
  `model_name`  varchar(64)  NOT NULL COMMENT '模型标识',
  `base_url`    varchar(255) DEFAULT NULL COMMENT '服务地址',
  `api_key`     varchar(255) DEFAULT NULL COMMENT 'API 密钥',
  `dimensions`  int          DEFAULT NULL COMMENT '向量维度(embedding 类型用)',
  `status`      tinyint      NOT NULL DEFAULT 1 COMMENT '状态: 0 停用 1 启用',
  `remark`      varchar(255) DEFAULT NULL COMMENT '备注',
  `creator`     varchar(64)  DEFAULT '' COMMENT '创建者',
  `create_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater`     varchar(64)  DEFAULT '' COMMENT '更新者',
  `update_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`     bit(1)       NOT NULL DEFAULT b'0' COMMENT '是否删除',
  `tenant_id`   bigint       NOT NULL DEFAULT 0 COMMENT '租户编号',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型配置';

-- 初始化: 默认本地 Embedding 模型(BGE-M3)与常用模型
INSERT INTO `ai_model_config` (`name`,`type`,`provider`,`model_name`,`base_url`,`dimensions`,`status`,`remark`,`tenant_id`)
VALUES
('BGE-M3 本地', 'embedding', 'OLLAMA', 'bge-m3', 'http://127.0.0.1:11434', 1024, 1, '默认本地向量模型', 1),
('TextEmbedding-3-v2', 'embedding', 'OPENAI', 'text-embedding-3-v2', 'https://api.openai.com/v1', NULL, 0, '云端备选(需密钥)', 1),
('DeepSeek-V3', 'chat', 'OPENAI', 'deepseek-chat', 'https://api.deepseek.com/v1', NULL, 1, '对话模型(示例)', 1),
('BGE-Reranker', 'rerank', 'OLLAMA', 'bge-reranker-v2-m3', 'http://127.0.0.1:11434', NULL, 1, '重排模型(示例)', 1);
