-- ============================================================
-- Prompt 管理菜单(2026-08-21)
-- 目标库: ruoyi-vue-pro (MySQL 8.0); 幂等: 已存在则跳过
-- 挂载: ④ AI 运行时(6741); 权限按钮 model:prompt:*
-- ============================================================
INSERT INTO `system_menu` (`id`, `name`, `type`, `path`, `component`, `parent_id`, `sort`, `status`, `visible`, `creator`, `create_time`, `updater`, `update_time`, `deleted`)
SELECT 6812, '提示词管理', 2, 'prompt', 'ai/prompt/index', 6741, 2, 0, b'1', 'admin', NOW(), 'admin', NOW(), b'0'
WHERE NOT EXISTS (SELECT 1 FROM `system_menu` WHERE `id` = 6812);

INSERT INTO `system_menu` (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `status`, `visible`, `creator`, `create_time`, `updater`, `update_time`, `deleted`)
SELECT 6813, '提示词查询', 'model:prompt:query', 3, 1, 6812, 0, b'1', 'admin', NOW(), 'admin', NOW(), b'0'
WHERE NOT EXISTS (SELECT 1 FROM `system_menu` WHERE `permission` = 'model:prompt:query');
INSERT INTO `system_menu` (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `status`, `visible`, `creator`, `create_time`, `updater`, `update_time`, `deleted`)
SELECT 6814, '新增提示词', 'model:prompt:create', 3, 2, 6812, 0, b'1', 'admin', NOW(), 'admin', NOW(), b'0'
WHERE NOT EXISTS (SELECT 1 FROM `system_menu` WHERE `permission` = 'model:prompt:create');
INSERT INTO `system_menu` (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `status`, `visible`, `creator`, `create_time`, `updater`, `update_time`, `deleted`)
SELECT 6815, '编辑提示词', 'model:prompt:update', 3, 3, 6812, 0, b'1', 'admin', NOW(), 'admin', NOW(), b'0'
WHERE NOT EXISTS (SELECT 1 FROM `system_menu` WHERE `permission` = 'model:prompt:update');
INSERT INTO `system_menu` (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `status`, `visible`, `creator`, `create_time`, `updater`, `update_time`, `deleted`)
SELECT 6816, '删除提示词', 'model:prompt:delete', 3, 4, 6812, 0, b'1', 'admin', NOW(), 'admin', NOW(), b'0'
WHERE NOT EXISTS (SELECT 1 FROM `system_menu` WHERE `permission` = 'model:prompt:delete');
