-- 停用 search-answer 提示词(双回答者收敛后无调用方, 2026-08-21; 仅执行一次)
-- 背景: 检索接口不再自己生成 answer, search-answer key 成孤儿, 保留停用版供追溯
UPDATE `ai_prompt` SET `status` = 0 WHERE `prompt_key` = 'search-answer' AND `status` = 1;
