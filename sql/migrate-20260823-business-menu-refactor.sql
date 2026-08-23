-- ============================================================
-- 知识平台业务菜单收敛(2026-08-23)
-- 目标: 左侧导航只表达业务能力，技术实体/上下文功能下沉到具体页面。
-- 说明:
--   1) 不删除底层页面、接口和权限，只隐藏不应作为顶级业务入口的菜单。
--   2) 版本、冲突、Chunk、检索调试仍可由知识库/文档/审核/评测页面进入。
--   3) 使用名称 + 产品目录 parent_id 做幂等更新，兼容已有环境。
-- ============================================================

-- ① 知识平台(6740): 业务入口统一命名
UPDATE `system_menu`
SET `name` = '知识库', `sort` = 10
WHERE `parent_id` = 6740 AND `name` = '知识库列表' AND `deleted` = b'0';

UPDATE `system_menu`
SET `name` = '文档管理', `sort` = 20
WHERE `parent_id` = 6740 AND `name` = '入库管线' AND `deleted` = b'0';

UPDATE `system_menu`
SET `name` = '审核发布', `sort` = 30
WHERE `parent_id` = 6740 AND `name` = '知识审核' AND `deleted` = b'0';

UPDATE `system_menu`
SET `name` = '质量评测', `sort` = 40
WHERE `parent_id` = 6740 AND `name` = '评测管理' AND `deleted` = b'0';

-- 技术实体/上下文功能不再作为左侧一级业务入口。
UPDATE `system_menu`
SET `visible` = b'0'
WHERE `parent_id` = 6740
  AND `name` IN ('AI 片段管理', '版本管理', '冲突裁决', '检索测试')
  AND `deleted` = b'0';

-- “知识运营中心”当前实际承载 Trace / Job，语义调整为运行观测。
UPDATE `system_menu`
SET `name` = '运行观测', `sort` = 50
WHERE `parent_id` = 6740 AND `name` = '知识运营中心' AND `deleted` = b'0';

UPDATE `system_menu` child
JOIN `system_menu` parent ON parent.`id` = child.`parent_id`
SET child.`name` = '文档链路'
WHERE parent.`parent_id` = 6740
  AND parent.`name` = '运行观测'
  AND child.`name` = '知识链路'
  AND child.`deleted` = b'0';

-- ④ AI 运行时(6741): “成本”页实际同时展示调用量/token/耗时/成功率。
UPDATE `system_menu`
SET `name` = '用量与成本'
WHERE `parent_id` = 6741 AND `name` = '成本管理' AND `deleted` = b'0';

-- 工作台: 去掉研发阶段原型入口，正式对话入口改为知识问答语义。
UPDATE `system_menu`
SET `visible` = b'0'
WHERE `name` = '工作台·原型版' AND `deleted` = b'0';

UPDATE `system_menu`
SET `name` = '知识问答工作台'
WHERE `name` = 'AI 对话工作台' AND `deleted` = b'0';
