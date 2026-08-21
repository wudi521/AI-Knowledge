# MinerU 文档解析服务部署说明

MinerU(上海 AI Lab)是中文布局感知 PDF 解析的事实标准: 版面分析/标题层级/表格结构/公式/图片定位/OCR 一站式输出结构化结果, 是我们"结构化解析"层的 PDF 主引擎。

## 1. 部署(Docker)

已加入 `deploy/yudao-cloud-dev/docker-compose.yml` 的 `mineru` 服务:

```bash
cd deploy/yudao-cloud-dev
docker compose up -d mineru
```

- 端口: 容器内 8000 → 宿主 18111
- 首次启动会下载模型(版面/OCR/表格, 数 GB, 挂载到 `mineru_models` 卷), 视网速可能 10~30 分钟
- **GPU 环境效果最佳**; compose 已带 nvidia GPU reservation, 无 GPU 时需去掉 `deploy.resources` 段(CPU 可跑, 大文档明显变慢)

## 2. 验证

```bash
# 服务健康(返回 200)
curl -sf http://127.0.0.1:18111/ || echo "未就绪"
```

用 curl 冒烟测试解析(替换为任意 PDF):

```bash
curl -s -X POST http://127.0.0.1:18111/file_parse -F "file=@/path/to/your.pdf"
# → {"code":0,"data":{"task_id":"..."}}
curl -s "http://127.0.0.1:18111/get_task_result?task_id=<task_id>"
# → {"code":0,"data":[{"page_idx":0,"md":"# 标题\n正文..."}, ...]}
```

## 3. 接入入库管线

ingestion-server `application-local.yaml`:

```yaml
yudao:
  ingestion:
    parser:
      mineru:
        enabled: true     # 部署完成后改为 true
        base-url: http://127.0.0.1:18111
        timeout-ms: 300000
        max-pages: 200
```

- `enabled=false`(默认)时 PDF 走 PDFBox 结构化(文本块+图片提取), 功能可用但无标题层级/复杂版面
- MinerU 调用失败自动降级 PDFBox, 不阻断入库

## 4. 视觉模型(图片理解/扫描页)

图片的语义描述与扫描页兜底识别依赖 `ai_model_config` 的 image 类型模型:

| 方式 | 配置 |
|---|---|
| Ollama(Docker, 已加 compose 服务) | `ollama pull qwen2.5-vl` 后, 模型管理页 image 类型: base-url=`http://127.0.0.1:11434/v1`, model=`qwen2.5-vl`, 状态启用 |
| 已有 LM Studio 视觉模型 | image 类型 base-url=`http://127.0.0.1:1234/v1`, model=`<视觉模型名>`, 启用 |

未启用 image 模型时: 图片不生成语义描述, 仅保留"所属章节/页码"占位(图片内容无法被检索命中), 入库不阻断。
