# MinerU 文档解析服务部署说明

MinerU(上海 AI Lab)是中文布局感知 PDF 解析的事实标准: 版面分析/标题层级/表格结构/公式/图片定位/OCR 一站式输出结构化结果, 是"结构化解析"层的 PDF 主引擎(可选组件, 未部署时自动降级 PDFBox 结构化, 不阻断入库)。

## 1. 部署方式(二选一)

### 方式 A: 本地 pip 安装(推荐, macOS/Linux 均支持)

```bash
# Python 3.10+, 建议独立 venv; 首次运行会自动下载模型(数 GB)
pip install "mineru[api]"          # 安装 API 服务组件
mineru-api --port 8000             # 启动 FastAPI 服务(监听 0.0.0.0:8000)
```

- 验证: `curl -sf http://127.0.0.1:8000/ && echo OK`
- 停止: Ctrl+C
- Apple Silicon(CPU/Metal)可跑, 大文档(>50页)解析偏慢属正常

### 方式 B: 社区 Docker 镜像(自行评估可信度)

MinerU **官方不在 Docker Hub 发布镜像**, 如需容器化请选用经过评估的社区镜像
(如 `alexsontop/mineru` 等), 映射端口 18111→8000 并按 README 示例配置:
`docker compose up -d mineru`(compose 中 mineru 服务已按此注释示例)。

## 2. API 接口(Java 侧 MineruParser 已对接)

| 接口 | 方法 | 说明 |
|---|---|---|
| `/file_parse` | POST multipart(file) | 上传文档, 返回 `{"code":0,"data":{"task_id":"..."}}` |
| `/get_task_result?task_id=xx` | GET | 轮询结果, 完成返回 `{"code":0,"data":[{"page_idx":0,"md":"# 标题\n正文..."}]}` |

## 3. 接入入库管线

ingestion-server `application-local.yaml`:

```yaml
yudao:
  ingestion:
    parser:
      mineru:
        enabled: true     # 服务就绪后改为 true
        base-url: http://127.0.0.1:18111
        timeout-ms: 300000
        max-pages: 200
```

- `enabled=false`(默认)时 PDF 走 PDFBox 结构化(文本块+图片提取), 功能可用但无标题层级/复杂版面
- MinerU 调用失败自动降级 PDFBox, 不阻断入库

## 4. 视觉模型(图片理解/扫描页识别)

图片的语义描述与扫描页兜底识别依赖 `ai_model_config` 的 image 类型模型:

| 方式 | 配置 |
|---|---|
| Ollama(Docker, compose 已含服务) | `docker compose up -d ollama` → `docker exec yudao-ollama ollama pull qwen2.5vl:7b` → 模型管理页 image 类型: base-url=`http://127.0.0.1:11434/v1`, model=`qwen2.5vl:7b`, 状态启用 |
| 已有 LM Studio 视觉模型 | image 类型 base-url=`http://127.0.0.1:1234/v1`, model=`<视觉模型名>`, 启用 |

未启用 image 模型时: 图片不生成语义描述, 仅保留"所属章节/页码"占位(图片内容无法被检索命中), 入库不阻断。
