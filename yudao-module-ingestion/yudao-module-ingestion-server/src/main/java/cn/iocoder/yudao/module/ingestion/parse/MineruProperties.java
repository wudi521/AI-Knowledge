package cn.iocoder.yudao.module.ingestion.parse;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MinerU 解析服务配置(yudao.ingestion.parser.mineru.*)
 * 默认关闭: 环境未部署 MinerU 服务时自动降级为 PDFBox/POI 结构化解析, 不阻断入库。
 */
@Component
@ConfigurationProperties(prefix = "yudao.ingestion.parser.mineru")
public class MineruProperties {

    /** 是否启用 MinerU 服务解析 PDF/扫描件 */
    private boolean enabled = false;

    /** MinerU API 服务地址(见 deploy/yudao-cloud-dev/docker-compose.yml mineru 服务) */
    private String baseUrl = "http://127.0.0.1:18111";

    /** 上传/轮询超时(ms) */
    private int timeoutMs = 300_000;

    /** 单文档最大页数(超限截断, 防超长文档拖垮服务) */
    private int maxPages = 200;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public int getMaxPages() {
        return maxPages;
    }

    public void setMaxPages(int maxPages) {
        this.maxPages = maxPages;
    }
}
