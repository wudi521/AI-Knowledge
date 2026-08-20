-- ============================================================
-- AI 知识系统菜单归拢到「① 知识平台」(2026-08-19)
-- 目标库: ruoyi-vue-pro (MySQL 8.0)
-- 背景: AI 知识库/审核/版本/冲突/检索/评测 原挂在 2758「AI 大模型」下,
--       与系统自带 AI 演示菜单(AI 对话/绘画/写作等)混杂; 用户要求归拢到知识平台
-- 动作:
--   1. 2758「AI 大模型」目录恢复隐藏(visible=0), 演示菜单随之不显示
--   2. 6 个 AI 知识系统菜单 parent_id -> 6740(① 知识平台), sort 104~109(紧邻知识库列表)
-- 幂等: 重复执行无副作用
-- ============================================================
UPDATE `system_menu` SET `visible` = b'0' WHERE `id` = 2758;

UPDATE `system_menu` SET `parent_id` = 6740, `sort` = 104 WHERE `id` = 2915; -- AI 知识库(完整页)
UPDATE `system_menu` SET `parent_id` = 6740, `sort` = 105 WHERE `id` = 6753; -- 知识审核
UPDATE `system_menu` SET `parent_id` = 6740, `sort` = 106 WHERE `id` = 6754; -- 版本管理
UPDATE `system_menu` SET `parent_id` = 6740, `sort` = 107 WHERE `id` = 6755; -- 冲突裁决
UPDATE `system_menu` SET `parent_id` = 6740, `sort` = 108 WHERE `id` = 6762; -- 检索测试
UPDATE `system_menu` SET `parent_id` = 6740, `sort` = 109 WHERE `id` = 6806; -- 评测管理
