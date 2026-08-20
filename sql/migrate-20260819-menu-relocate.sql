-- ============================================================
-- AI 知识系统菜单归拢到「① 知识平台」(2026-08-19, 修订版)
-- 目标库: ruoyi-vue-pro (MySQL 8.0)
-- 背景: 知识审核/版本管理/冲突裁决/检索测试/评测管理(均为 2026-08-17~19 我方创建)
--       原挂在 2758「AI 大模型」下, 与系统自带 AI 演示菜单混杂; 归拢到 6740「① 知识平台」。
-- 注意: 2915「AI 知识库」为系统原有菜单(2025-02-28), 不动, 保持 parent=2758。
-- 动作:
--   1. 2758「AI 大模型」目录保持隐藏(visible=0, 系统演示菜单不显示)
--   2. 5 个我方菜单 parent_id -> 6740(① 知识平台), sort 105~109
--   3. 2915 若被误移则还原(幂等保护)
-- 幂等: 重复执行无副作用
-- ============================================================
UPDATE `system_menu` SET `visible` = b'0' WHERE `id` = 2758;

UPDATE `system_menu` SET `parent_id` = 6740, `sort` = 105 WHERE `id` = 6753; -- 知识审核
UPDATE `system_menu` SET `parent_id` = 6740, `sort` = 106 WHERE `id` = 6754; -- 版本管理
UPDATE `system_menu` SET `parent_id` = 6740, `sort` = 107 WHERE `id` = 6755; -- 冲突裁决
UPDATE `system_menu` SET `parent_id` = 6740, `sort` = 108 WHERE `id` = 6762; -- 检索测试
UPDATE `system_menu` SET `parent_id` = 6740, `sort` = 109 WHERE `id` = 6806; -- 评测管理

-- 还原保护: AI 知识库(系统原有)保持 2758 下(误移则还原)
UPDATE `system_menu` SET `parent_id` = 2758, `sort` = 5 WHERE `id` = 2915;
