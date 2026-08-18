-- ----------------------------
-- 对话工作台按钮权限(父菜单 6736 AI 对话工作台, type=3 按钮)
-- 幂等: WHERE NOT EXISTS(permission) 已存在则跳过
-- 执行: docker exec -i yudao-mysql mysql -uroot -p123456 --default-character-set=utf8mb4 ruoyi-vue-pro < menu-chat.sql
-- ----------------------------
INSERT INTO `system_menu` (`name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `component_name`, `status`, `visible`, `keep_alive`, `always_show`, `creator`, `create_time`, `updater`, `update_time`, `deleted`)
SELECT '发送消息', 'chat:chat:send', 3, 1, 6736, '', '', '', NULL, 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `system_menu` WHERE `permission` = 'chat:chat:send' AND `deleted` = b'0');

INSERT INTO `system_menu` (`name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `component_name`, `status`, `visible`, `keep_alive`, `always_show`, `creator`, `create_time`, `updater`, `update_time`, `deleted`)
SELECT '转人工', 'chat:conversation:transfer', 3, 2, 6736, '', '', '', NULL, 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `system_menu` WHERE `permission` = 'chat:conversation:transfer' AND `deleted` = b'0');

INSERT INTO `system_menu` (`name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `component_name`, `status`, `visible`, `keep_alive`, `always_show`, `creator`, `create_time`, `updater`, `update_time`, `deleted`)
SELECT '接管会话', 'chat:conversation:take-over', 3, 3, 6736, '', '', '', NULL, 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `system_menu` WHERE `permission` = 'chat:conversation:take-over' AND `deleted` = b'0');

INSERT INTO `system_menu` (`name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `component_name`, `status`, `visible`, `keep_alive`, `always_show`, `creator`, `create_time`, `updater`, `update_time`, `deleted`)
SELECT '会话查询', 'chat:conversation:query', 3, 4, 6736, '', '', '', NULL, 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `system_menu` WHERE `permission` = 'chat:conversation:query' AND `deleted` = b'0');
