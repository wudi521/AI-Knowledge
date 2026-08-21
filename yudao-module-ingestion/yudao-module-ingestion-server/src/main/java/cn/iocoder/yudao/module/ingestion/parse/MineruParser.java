package cn.iocoder.yudao.module.ingestion.parse;

import cn.hutool.core.io.FileUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.ingestion.split.ParsedDocument;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * MinerU 解析器(中文布局感知, PDF/扫描件主解析引擎): 上传 → 轮询取结构化 JSON → ParsedDocument。
 * <p>
 * 依赖独立部署的 MinerU API 服务(见 deploy/yudao-cloud-dev/docker-compose.yml mineru 服务);
 * 未启用/调用失败时由上层降级到 PdfParser(PDFBox 结构化), 不阻断入库。
 */
@Slf4j
@Component
public class MineruParser implements DocumentParser {

    private static final int POLL_INTERVAL_MS = 2_000;
    private static final int MAX_POLL_ATTEMPTS = 150;

    @Resource
    private MineruProperties props;

    @Override
    public String parse(String filePath, String docType) throws Exception {
        return parseStructured(filePath, docType).toPlainText();
    }

    @Override
    public ParsedDocument parseStructured(String filePath, String docType) throws Exception {
        if (!props.isEnabled()) {
            throw new UnsupportedOperationException("MinerU 未启用(yudao.ingestion.parser.mineru.enabled=false)");
        }
        String base = props.getBaseUrl();
        File file = new File(filePath);
        // 1. 上传 → task_id
        String taskId;
        try (HttpResponse resp = HttpRequest.post(base + "/file_parse")
                .form("file", file)
                .timeout(props.getTimeoutMs())
                .execute()) {
            if (!resp.isOk()) {
                throw new IllegalStateException("MinerU 上传失败: HTTP " + resp.getStatus());
            }
            JSONObject json = JSONUtil.parseObj(resp.body());
            if (json.getInt("code", -1) != 0) {
                throw new IllegalStateException("MinerU 上传失败: " + resp.body());
            }
            JSONObject data = json.getJSONObject("data");
            taskId = data == null ? null : data.getStr("task_id");
            if (taskId == null) {
                throw new IllegalStateException("MinerU 响应无 task_id: " + resp.body());
            }
        }
        // 2. 轮询结果(data 为数组即完成)
        Object result = null;
        for (int i = 0; i < MAX_POLL_ATTEMPTS; i++) {
            String body = HttpRequest.get(base + "/get_task_result?task_id=" + taskId)
                    .timeout(30_000).execute().body();
            JSONObject json = JSONUtil.parseObj(body);
            Object data = json.get("data");
            if (data instanceof JSONArray) {
                result = data;
                break;
            }
            if (data instanceof JSONObject obj && "failed".equalsIgnoreCase(obj.getStr("state"))) {
                throw new IllegalStateException("MinerU 解析失败: " + body);
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        if (result == null) {
            throw new IllegalStateException("MinerU 轮询超时: task_id=" + taskId);
        }
        ParsedDocument doc = MineruJsonConverter.convert(result, docType);
        doc.setDocType(docType == null ? "PDF" : docType);
        return doc;
    }
}
