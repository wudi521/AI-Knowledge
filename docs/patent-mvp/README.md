# 专利领域 MVP v0.1 · 运行文档

> 基于 AI-Knowledge(后端) + AI-Knowledge-admin(前端 apps/web-antd) · 分支 feat/patent-mvp-v0.1

## 一、能力清单(已实现)

| 能力 | 状态 |
|---|---|
| 知识库领域标识(PATENT/GENERAL) + 前端领域选择 | ✅ |
| 专利 PDF 解析: 著录信息提取(申请号/公布号/申请人/IPC/名称/摘要) | ✅ 冒烟17项全过 |
| 权利要求解析: 完整保留/跨行合并/从属依赖(权利要求8→1-7) | ✅ |
| 专利切片: 章节识别(权利要求书/说明书/摘要)+权利要求单Chunk+搜索头+metadata | ✅ |
| PATENT 跳过客服审核 → 人工确认 → 两阶段发布 | ✅ |
| Chat 知识库绑定(未选禁止) + 专利查询提示词 + 产品门禁关闭 | ✅ |
| 回答来源卡片(文档名/申请号/公布号/章节/权利要求/页码/引用原文) | ✅ |
| Knowledge Ops: Trace 数据模型 + Document Trace/Query Trace/任务中心接口+前端三页 | ✅ |
| 知识库工作空间(概览/资料/知识内容/质量/设置) + Chunk弹窗专利字段 + 文档专利列 | ✅ |

## 二、运行步骤(部署后)

### 1. 环境变量(IDEA Run Configuration)
```bash
YUDAO_INTERNAL_AUTH_SECRET=<强密钥>
YUDAO_SECRET_MASTER_KEY=$(openssl rand -hex 32)
```

### 2. 重启服务 + 迁移
重启 model/knowledge/ingestion/retrieval/evidence; yudao-server 启动自动执行 Flyway V2~V12(已手动执行的 V2-V9 由 flyway_schema_history 标记跳过; V10-V12 新迁移自动跑, 或手动执行:
```bash
docker exec -i yudao-mysql mysql -uroot -p123456 ruoyi-vue-pro < yudao-server/src/main/resources/db/migration/V10__knowledge_ops_trace.sql
docker exec -i yudao-mysql mysql --default-character-set=utf8mb4 -uroot -p123456 ruoyi-vue-pro < yudao-server/src/main/resources/db/migration/V11__ops_menu.sql
docker exec -i yudao-mysql mysql --default-character-set=utf8mb4 -uroot -p123456 ruoyi-vue-pro < yudao-server/src/main/resources/db/migration/V12__workspace_menu.sql
```
(注意: 含中文的 SQL 必须加 --default-character-set=utf8mb4, 否则中文乱码)

### 3. 前端
```bash
cd AI-Knowledge-admin && pnpm install --frozen-lockfile && pnpm dev:antd
```

### 4. Happy Path 演示
1. 知识库 → 新建 → 领域选"专利(PATENT)" → 创建
2. 知识库列表 → "进入空间" → 资料 → 上传三份 PDF(docs/专利文档/)
3. 等待解析(查看空间内 解析状态/异常; 或 Knowledge Ops 任务中心/知识链路看时间轴)
4. 发布(审核 → 发布, 两阶段: 校验→索引→置PUBLISHED)
5. 工作台(/wb/workbench-vben) → 顶部选择专利知识库 → 提问(如"权利要求8引用了哪些在先权利要求?")
6. 回答下方来源卡片展示 文档名/公布号/申请号/章节/权利要求/页码

### 5. 12 条必测问题验证
```bash
# 部署后执行(需 TOKEN + KB_ID)
TOKEN=<登录token> KB_ID=<专利知识库id> bash deploy/patent-verify.sh
```
12 条覆盖: 著录(BIBLIOGRAPHIC)/权利要求数/跨文档/权利要求限定/依赖关系/技术领域/技术方案/
拒答(授权状态/医疗效果/US专利)/数值/精确标识/访问范围——对应 docs/patent_mvp_golden_cases.json

## 三、Knowledge Ops 入口
- 菜单: ①知识平台 → 知识运营中心 → 知识链路/查询链路/任务中心
- 知识链路: 输入 documentId → 文档/版本/入库时间轴/片段
- 查询链路: 输入 traceId → 检索轨迹/阶段
- 任务中心: 任务分页 + 阶段时间轴

## 四、已知说明
- 沙箱环境无法端到端(Java 直连受限), 解析/切分逻辑经冒烟测试验证(真实 PDF 文本); 12 条必测需部署后跑 patent-verify.sh
- 超级管理员当前拥有全部菜单; 角色权限细化后续配置
- MinerU 未部署时 PDF 走 PDFBox 降级(文本层正常, 扫描件无法识别)
