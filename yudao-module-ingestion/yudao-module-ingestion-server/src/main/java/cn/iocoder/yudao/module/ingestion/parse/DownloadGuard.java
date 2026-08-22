package cn.iocoder.yudao.module.ingestion.parse;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.InetAddress;
import java.net.URI;

/**
 * 文件下载安全防护(A3): storagePath 只允许从配置的下载源白名单(MinIO 服务)拉取——
 * 防止 SSRF(任意 URL 下载内网资源/云元数据)、超大文件、伪造文件类型。
 * <p>
 * 白名单优于黑名单: 只信任配置的 MinIO host, 攻击者无法通过任意 URL 触发服务端请求;
 * DNS rebinding 由 host 精确/后缀匹配缓解(host 名不可控时无法绕过白名单)。
 */
@Slf4j
@Component
public class DownloadGuard {

    /** 默认最大文件字节(100MB) */
    private static final long DEFAULT_MAX_BYTES = 100L * 1024 * 1024;

    @Value("${yudao.ingestion.download.allowed-hosts:127.0.0.1,localhost}")
    private String allowedHosts;

    @Value("${yudao.ingestion.download.max-file-bytes:104857600}")
    private long maxFileBytes;

    /**
     * 校验下载 URL 来源(SSRF 防护): scheme 白名单 + host 白名单
     *
     * @throws IllegalArgumentException URL 非法/来源不允许
     */
    public void validateUrl(String url) {
        if (StrUtil.isBlank(url)) {
            throw new IllegalArgumentException("下载地址为空");
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            throw new IllegalArgumentException("下载地址非法: " + StrUtil.maxLength(url, 120));
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("下载地址仅支持 http/https, 当前: " + scheme);
        }
        String host = uri.getHost();
        if (StrUtil.isBlank(host)) {
            throw new IllegalArgumentException("下载地址缺少 host");
        }
        if (!isHostAllowed(host)) {
            log.warn("[validateUrl][下载源 {} 不在白名单, 拒绝 SSRF]", host);
            throw new IllegalArgumentException("下载源不在白名单: " + host);
        }
    }

    /**
     * 受限下载: 预检 Content-Length + 下载 + 落地后大小校验
     *
     * @param url   下载地址(已通过 validateUrl)
     * @param target 目标临时文件
     */
    public void download(String url, File target) {
        // 预检 Content-Length(超限拒绝, 避免下载超大文件)
        long contentLength = -1;
        try {
            String len = cn.hutool.http.HttpRequest.get(url)
                    .timeout(30_000).execute().header("Content-Length");
            if (StrUtil.isNotBlank(len)) {
                contentLength = Long.parseLong(len.trim());
            }
        } catch (Exception ignored) {
            // 预检失败(如服务端无 Content-Length)不阻断, 由落地后校验兜底
        }
        if (contentLength > maxFileBytes) {
            throw new IllegalArgumentException("文件超过大小限制(" + (maxFileBytes / 1024 / 1024) + "MB): " + contentLength);
        }
        // 下载(30s 连接/读取超时, 防挂起拖垮入库线程)
        cn.hutool.http.HttpUtil.downloadFile(url, target, 30_000);
        // 落地后大小校验(Content-Length 缺失/被绕过时的兜底)
        if (target.length() > maxFileBytes) {
            throw new IllegalArgumentException("文件超过大小限制: " + target.length());
        }
    }

    /**
     * 文件 magic number 校验(防伪造文件类型/压缩炸弹入口): 与声明类型不匹配时拒绝
     *
     * @param file    已下载的临时文件
     * @param docType 声明类型 PDF/WORD/EXCEL/PPT/IMAGE/TXT/MD
     */
    public void validateMagic(File file, String docType) {
        if (file == null || file.length() < 4) {
            throw new IllegalArgumentException("文件为空或过小, 无法校验类型");
        }
        byte[] head = new byte[8];
        try (java.io.InputStream in = new java.io.FileInputStream(file)) {
            int read = in.read(head);
            if (read < 4) {
                throw new IllegalArgumentException("文件读取失败或过小");
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("文件读取失败: " + e.getMessage());
        }
        boolean ok = switch (docType == null ? "" : docType.toUpperCase()) {
            case "PDF" -> matches(head, new byte[]{'%', 'P', 'D', 'F'});
            case "WORD", "EXCEL", "PPT" -> matches(head, new byte[]{0x50, 0x4B, 0x03, 0x04})      // OOXML(zip)
                    || matches(head, new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0}); // OLE2 旧格式
            case "IMAGE" -> matches(head, new byte[]{(byte) 0x89, 'P', 'N', 'G'})
                    || matches(head, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})
                    || matches(head, new byte[]{'G', 'I', 'F', '8'})
                    || matches(head, new byte[]{'R', 'I', 'F', 'F'});                          // png/jpg/gif/webp
            default -> true; // TXT/MD 文本不校验
        };
        if (!ok) {
            throw new IllegalArgumentException("文件类型与声明不符(magic number 校验失败): " + docType);
        }
    }

    private boolean isHostAllowed(String host) {
        String h = host.toLowerCase();
        for (String allowed : allowedHosts.split(",")) {
            String a = allowed.trim().toLowerCase();
            if (a.isEmpty()) {
                continue;
            }
            if (h.equals(a) || h.endsWith("." + a)) { // 精确或子域后缀匹配
                return true;
            }
        }
        return false;
    }

    private static boolean matches(byte[] data, byte[] magic) {
        if (data.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (data[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }
}
