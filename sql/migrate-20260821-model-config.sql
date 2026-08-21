-- 模型配置全量数据库化(2026-08-21; ALTER 部分仅执行一次, 数据部分幂等可重复执行)
-- 1) ai_model_config 增加成本单价列(in/out 每百万 token 元, 承接原 yaml yudao.model.pricing.*)
-- 2) 清理无用配置(deepseek-chat 401 / text-embedding-3-v2 / bge-m3@11434 / bge-reranker@11434)
-- 3) 写入实际使用的 3 个模型(embedding/chat/rerank) + 1 条停用 image 视觉模型

-- ① 加单价列(仅执行一次)
ALTER TABLE `ai_model_config`
  ADD COLUMN `in_per_mtok`  decimal(10,4) NULL DEFAULT NULL COMMENT '输入单价(每百万token, 元)' AFTER `dimensions`,
  ADD COLUMN `out_per_mtok` decimal(10,4) NULL DEFAULT NULL COMMENT '输出单价(每百万token, 元)' AFTER `in_per_mtok`;

-- ② 软删无用配置(幂等)
UPDATE `ai_model_config`
SET `deleted` = b'1', `updater` = 'migrate-20260821'
WHERE `deleted` = b'0'
  AND (
        `model_name` IN ('deepseek-chat', 'text-embedding-3-v2')
     OR `base_url` LIKE '%11434%'
  );

-- ③ 写入真实模型(幂等: 按 type+model_name 不存在才插入)
INSERT INTO `ai_model_config`
  (`name`,`type`,`scenario`,`priority`,`provider`,`model_name`,`base_url`,`api_key`,`dimensions`,`status`,`remark`,`tenant_id`,`creator`,`in_per_mtok`,`out_per_mtok`)
SELECT * FROM (
  SELECT '本地 Embedding(BGE-M3)' AS name, 'embedding' AS type, '*' AS scenario, 0 AS priority,
         'LM_STUDIO' AS provider, 'text-embedding-bge-m3' AS model_name, 'http://127.0.0.1:1234/v1' AS base_url,
         NULL AS api_key, 1024 AS dimensions, 1 AS status,
         'LM Studio 本地向量模型(1234 端口)' AS remark, 1 AS tenant_id, 'migrate-20260821' AS creator,
         0.1 AS in_per_mtok, 0.1 AS out_per_mtok
) t
WHERE NOT EXISTS (SELECT 1 FROM `ai_model_config` WHERE `deleted` = b'0' AND `type` = 'embedding' AND `model_name` = 'text-embedding-bge-m3');

INSERT INTO `ai_model_config`
  (`name`,`type`,`scenario`,`priority`,`provider`,`model_name`,`base_url`,`api_key`,`dimensions`,`status`,`remark`,`tenant_id`,`creator`,`in_per_mtok`,`out_per_mtok`)
SELECT * FROM (
  SELECT '本地 Chat(Qwen3-8B)' AS name, 'chat' AS type, '*' AS scenario, 0 AS priority,
         'LM_STUDIO' AS provider, 'qwen/qwen3-8b' AS model_name, 'http://127.0.0.1:1234/v1' AS base_url,
         NULL AS api_key, NULL AS dimensions, 1 AS status,
         'LM Studio 本地对话模型(1234 端口)' AS remark, 1 AS tenant_id, 'migrate-20260821' AS creator,
         0.5 AS in_per_mtok, 1.5 AS out_per_mtok
) t
WHERE NOT EXISTS (SELECT 1 FROM `ai_model_config` WHERE `deleted` = b'0' AND `type` = 'chat' AND `model_name` = 'qwen/qwen3-8b');

INSERT INTO `ai_model_config`
  (`name`,`type`,`scenario`,`priority`,`provider`,`model_name`,`base_url`,`api_key`,`dimensions`,`status`,`remark`,`tenant_id`,`creator`,`in_per_mtok`,`out_per_mtok`)
SELECT * FROM (
  SELECT '本地 Rerank(BGE-Reranker)' AS name, 'rerank' AS type, '*' AS scenario, 0 AS priority,
         'LLAMA_CPP' AS provider, 'bge-reranker-v2-m3' AS model_name, 'http://127.0.0.1:1236/v1' AS base_url,
         NULL AS api_key, NULL AS dimensions, 1 AS status,
         'llama.cpp 本地重排模型(1236 端口, deploy/llama-rerank.sh)' AS remark, 1 AS tenant_id, 'migrate-20260821' AS creator,
         0.1 AS in_per_mtok, 0.1 AS out_per_mtok
) t
WHERE NOT EXISTS (SELECT 1 FROM `ai_model_config` WHERE `deleted` = b'0' AND `type` = 'rerank' AND `model_name` = 'bge-reranker-v2-m3');

-- ④ 预留视觉模型(停用; 启用需在模型管理页填写实际视觉模型服务地址)
INSERT INTO `ai_model_config`
  (`name`,`type`,`scenario`,`priority`,`provider`,`model_name`,`base_url`,`api_key`,`dimensions`,`status`,`remark`,`tenant_id`,`creator`,`in_per_mtok`,`out_per_mtok`)
SELECT * FROM (
  SELECT '视觉模型(Qwen2.5-VL)' AS name, 'image' AS type, '*' AS scenario, 0 AS priority,
         'LM_STUDIO' AS provider, 'qwen2.5-vl' AS model_name, 'http://127.0.0.1:1234/v1' AS base_url,
         NULL AS api_key, NULL AS dimensions, 0 AS status,
         '视觉模型(图片理解/扫描页识别); 默认停用, 启用前请确认服务地址与模型名' AS remark, 1 AS tenant_id, 'migrate-20260821' AS creator,
         1.0 AS in_per_mtok, 2.0 AS out_per_mtok
) t
WHERE NOT EXISTS (SELECT 1 FROM `ai_model_config` WHERE `deleted` = b'0' AND `type` = 'image' AND `model_name` = 'qwen2.5-vl');
