-- V15: 修正 legacy 会话领域语义 —— 仅真实绑定 KB 的会话才允许保留 domain_code
-- RF-08: 未绑定知识库的历史会话(如 V14 回填阶段默认 GENERAL 的旧记录)领域语义无意义,
-- 置为 NULL; 真实绑定 KB 的会话在创建时快照 domain_code, 不受影响。
UPDATE `ai_conversation`
SET `domain_code` = NULL
WHERE `kb_id` IS NULL;
