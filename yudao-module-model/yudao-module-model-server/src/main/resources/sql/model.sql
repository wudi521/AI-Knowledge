-- 模型配置表(模型网关: chat / embedding / rerank / image 统一配置)
CREATE TABLE IF NOT EXISTS `ai_model_config` (
  `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT '编号',
  `name`        varchar(64)  NOT NULL COMMENT '名称',
  `type`        varchar(16)  NOT NULL COMMENT '类型: chat / embedding / rerank / image',
  `scenario`    varchar(64)  NOT NULL DEFAULT '*' COMMENT '场景标识(如 A/B; *=默认场景)',
  `priority`    int          NOT NULL DEFAULT 0 COMMENT '降级顺序(同类型同场景内, 小者优先)',
  `provider`    varchar(32)  NOT NULL DEFAULT 'OLLAMA' COMMENT '供应商: OLLAMA / OPENAI / ALIYUN / XINFERENCE / LM_STUDIO / LLAMA_CPP',
  `model_name`  varchar(64)  NOT NULL COMMENT '模型标识',
  `base_url`    varchar(255) DEFAULT NULL COMMENT '服务地址',
  `api_key`     varchar(255) DEFAULT NULL COMMENT 'API 密钥',
  `dimensions`  int          DEFAULT NULL COMMENT '向量维度(embedding 类型用)',
  `in_per_mtok` decimal(10,4) DEFAULT NULL COMMENT '输入单价(每百万token, 元)',
  `out_per_mtok` decimal(10,4) DEFAULT NULL COMMENT '输出单价(每百万token, 元)',
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

-- 初始化: 实际使用的 3 个模型(与 sql/migrate-20260821-model-config.sql 一致, 幂等)
INSERT INTO `ai_model_config` (`name`,`type`,`scenario`,`priority`,`provider`,`model_name`,`base_url`,`dimensions`,`status`,`remark`,`tenant_id`,`in_per_mtok`,`out_per_mtok`)
SELECT * FROM (
  SELECT '本地 Embedding(BGE-M3)' name, 'embedding' type, '*' scenario, 0 priority,
         'LM_STUDIO' provider, 'text-embedding-bge-m3' model_name, 'http://127.0.0.1:1234/v1' base_url,
         1024 dimensions, 1 status, 'LM Studio 本地向量模型(1234 端口)' remark, 1 tenant_id, 0.1 in_per_mtok, 0.1 out_per_mtok
) t
WHERE NOT EXISTS (SELECT 1 FROM `ai_model_config` WHERE `deleted` = b'0' AND `type` = 'embedding' AND `model_name` = 'text-embedding-bge-m3');

INSERT INTO `ai_model_config` (`name`,`type`,`scenario`,`priority`,`provider`,`model_name`,`base_url`,`dimensions`,`status`,`remark`,`tenant_id`,`in_per_mtok`,`out_per_mtok`)
SELECT * FROM (
  SELECT '本地 Chat(Qwen3-8B)' name, 'chat' type, '*' scenario, 0 priority,
         'LM_STUDIO' provider, 'qwen/qwen3-8b' model_name, 'http://127.0.0.1:1234/v1' base_url,
         NULL dimensions, 1 status, 'LM Studio 本地对话模型(1234 端口)' remark, 1 tenant_id, 0.5 in_per_mtok, 1.5 out_per_mtok
) t
WHERE NOT EXISTS (SELECT 1 FROM `ai_model_config` WHERE `deleted` = b'0' AND `type` = 'chat' AND `model_name` = 'qwen/qwen3-8b');

INSERT INTO `ai_model_config` (`name`,`type`,`scenario`,`priority`,`provider`,`model_name`,`base_url`,`dimensions`,`status`,`remark`,`tenant_id`,`in_per_mtok`,`out_per_mtok`)
SELECT * FROM (
  SELECT '本地 Rerank(BGE-Reranker)' name, 'rerank' type, '*' scenario, 0 priority,
         'LLAMA_CPP' provider, 'bge-reranker-v2-m3' model_name, 'http://127.0.0.1:1236/v1' base_url,
         NULL dimensions, 1 status, 'llama.cpp 本地重排模型(1236 端口, deploy/llama-rerank.sh)' remark, 1 tenant_id, 0.1 in_per_mtok, 0.1 out_per_mtok
) t
WHERE NOT EXISTS (SELECT 1 FROM `ai_model_config` WHERE `deleted` = b'0' AND `type` = 'rerank' AND `model_name` = 'bge-reranker-v2-m3');

-- 预留视觉模型(停用; 启用需在模型管理页填写实际视觉模型服务地址)
INSERT INTO `ai_model_config` (`name`,`type`,`scenario`,`priority`,`provider`,`model_name`,`base_url`,`dimensions`,`status`,`remark`,`tenant_id`,`in_per_mtok`,`out_per_mtok`)
SELECT * FROM (
  SELECT '视觉模型(Qwen2.5-VL)' name, 'image' type, '*' scenario, 0 priority,
         'LM_STUDIO' provider, 'qwen2.5-vl' model_name, 'http://127.0.0.1:1234/v1' base_url,
         NULL dimensions, 0 status, '视觉模型(图片理解/扫描页识别); 默认停用, 启用前请确认服务地址与模型名' remark, 1 tenant_id, 1.0 in_per_mtok, 2.0 out_per_mtok
) t
WHERE NOT EXISTS (SELECT 1 FROM `ai_model_config` WHERE `deleted` = b'0' AND `type` = 'image' AND `model_name` = 'qwen2.5-vl');
