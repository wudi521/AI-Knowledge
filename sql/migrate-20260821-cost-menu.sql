-- M8 成本管理菜单(2026-08-21; 仅执行一次): ④ AI 运行时(6741) → 成本管理
-- 页面 id=6817 (sort 3), 按钮权限 6818-6821
INSERT INTO `system_menu` (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `component_name`, `status`, `visible`, `keep_alive`, `always_show`, `creator`, `create_time`, `updater`, `update_time`, `deleted`)
VALUES
  (6817, '成本管理', '', 2, 3, 6741, 'cost', 'ep:money', 'ai/cost/index', NULL, 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'),
  (6818, '成本查询', 'model:cost:query', 3, 1, 6817, NULL, NULL, NULL, NULL, 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0');
