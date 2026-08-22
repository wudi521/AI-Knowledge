-- V12: 知识空间 Workspace 菜单(知识库业务工作空间)
-- 回滚: DELETE FROM system_role_menu WHERE menu_id=6823; DELETE FROM system_menu WHERE id=6823;
INSERT INTO `system_menu` (`id`,`name`,`permission`,`type`,`sort`,`parent_id`,`path`,`icon`,`component`,`component_name`,`status`,`visible`,`keep_alive`,`always_show`,`creator`,`create_time`,`updater`,`update_time`,`deleted`) VALUES
(6823, '知识空间', 'knowledge:knowledge-base:query', 2, 4, 6740, 'workspace', 'ep:home-filled', 'ai/knowledge/workspace/index', 'KnowledgeWorkspace', 0, 0, 1, 0, '1', NOW(), '1', NOW(), b'0');
INSERT INTO `system_role_menu` (`role_id`,`menu_id`,`creator`,`create_time`,`updater`,`update_time`,`deleted`,`tenant_id`) VALUES
(1, 6823, '1', NOW(), '1', NOW(), b'0', 0);
