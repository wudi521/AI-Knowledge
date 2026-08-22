-- V11: Knowledge Ops 菜单(总任务书 §4.2 运营层; 超级管理员当前拥有全部菜单)
-- 回滚: DELETE FROM system_role_menu WHERE menu_id IN (6819,6820,6821,6822);
--        DELETE FROM system_menu WHERE id IN (6819,6820,6821,6822);

INSERT INTO `system_menu` (`id`,`name`,`permission`,`type`,`sort`,`parent_id`,`path`,`icon`,`component`,`component_name`,`status`,`visible`,`keep_alive`,`always_show`,`creator`,`create_time`,`updater`,`update_time`,`deleted`) VALUES
(6819, '知识运营中心', '', 1, 110, 6740, 'ops', 'ep:data-analysis', '', 'KnowledgeOps', 0, 1, 1, 1, '1', NOW(), '1', NOW(), b'0'),
(6820, '知识链路', 'knowledge:ops:document-trace', 2, 1, 6819, 'document-trace', '', 'ai/ops/document-trace', 'OpsDocumentTrace', 0, 1, 1, 1, '1', NOW(), '1', NOW(), b'0'),
(6821, '查询链路', 'retrieval:ops:query-trace', 2, 2, 6819, 'query-trace', '', 'ai/ops/query-trace', 'OpsQueryTrace', 0, 1, 1, 1, '1', NOW(), '1', NOW(), b'0'),
(6822, '任务中心', 'ingestion:ops:jobs', 2, 3, 6819, 'jobs', '', 'ai/ops/jobs', 'OpsJobs', 0, 1, 1, 1, '1', NOW(), '1', NOW(), b'0');

-- 超级管理员(super_admin)菜单关联
INSERT INTO `system_role_menu` (`role_id`,`menu_id`,`creator`,`create_time`,`updater`,`update_time`,`deleted`,`tenant_id`) VALUES
(1, 6819, '1', NOW(), '1', NOW(), b'0', 0),
(1, 6820, '1', NOW(), '1', NOW(), b'0', 0),
(1, 6821, '1', NOW(), '1', NOW(), b'0', 0),
(1, 6822, '1', NOW(), '1', NOW(), b'0', 0);
