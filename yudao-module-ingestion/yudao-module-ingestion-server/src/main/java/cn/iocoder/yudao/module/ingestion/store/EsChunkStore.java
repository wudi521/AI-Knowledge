package cn.iocoder.yudao.module.ingestion.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ES BM25 写入(low-level RestClient, 兼容 8.x)
 */
@Slf4j
@Component
public class EsChunkStore {

    @Value("${spring.elasticsearch.uris:http://127.0.0.1:9200}")
    private String uris;
    @Value("${yudao.ai.es.index:ai_chunk_bm25}")
    private String index;

    private RestClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        try {
            parseAndInit();
        } catch (Exception e) {
            // 解析失败不阻断启动, 仅记录日志(后续写入会因 client 为空抛异常)
            log.error("[init][ES uris 解析失败, 跳过初始化: {}]", uris, e);
        }
    }

    private void parseAndInit() {
        // 多节点逗号分隔时取第一个; 兼容 scheme/无端口/IPv6/尾随路径
        String uri = uris.split(",")[0].trim();
        String scheme = uri.startsWith("https") ? "https" : "http";
        String rest = uri.replaceAll("^https?://", "");
        int slash = rest.indexOf('/');
        if (slash >= 0) {
            rest = rest.substring(0, slash);
        }
        String host;
        int port;
        if (rest.startsWith("[")) {
            // IPv6: [::1]:9200
            int close = rest.indexOf(']');
            host = rest.substring(1, close);
            String after = rest.substring(close + 1);
            port = after.startsWith(":") ? Integer.parseInt(after.substring(1)) : 9200;
        } else {
            int colon = rest.lastIndexOf(':');
            if (colon >= 0) {
                host = rest.substring(0, colon);
                port = Integer.parseInt(rest.substring(colon + 1));
            } else {
                host = rest;
                port = 9200;
            }
        }
        client = RestClient.builder(new HttpHost(host, port, scheme)).build();
        createIndexIfAbsent();
    }

    private void createIndexIfAbsent() {
        try {
            Request exists = new Request("HEAD", "/" + index);
            int code = client.performRequest(exists).getStatusLine().getStatusCode();
            if (code == 200) {
                return;
            }
        } catch (ResponseException e) {
            // HEAD 404: 索引不存在(低层 RestClient 对 4xx 状态抛 ResponseException)
            if (e.getResponse().getStatusLine().getStatusCode() != 404) {
                log.error("[init][ES 索引检查失败, 状态码 {}]", e.getResponse().getStatusLine().getStatusCode(), e);
                return;
            }
        } catch (Exception e) {
            log.error("[init][ES 索引检查失败]", e);
            return;
        }
        try {
            Map<String, Object> mapping = new HashMap<>();
            mapping.put("mappings", Map.of("properties", Map.of(
                    "chunk_id", Map.of("type", "long"),
                    "tenant_id", Map.of("type", "long"),
                    "kb_id", Map.of("type", "long"),
                    "status", Map.of("type", "keyword"),
                    "content", Map.of("type", "text", "analyzer", "ik_max_word", "search_analyzer", "ik_smart")
            )));
            Request create = new Request("PUT", "/" + index);
            create.setJsonEntity(objectMapper.writeValueAsString(mapping));
            client.performRequest(create);
            log.info("[init][ES 索引 {} 创建完成]", index);
        } catch (Exception e) {
            log.error("[init][ES 索引创建失败]", e);
        }
    }

    /**
     * 写入一条 BM25 文档
     */
    public void insertChunk(Long chunkId, Long tenantId, Long kbId, String content) {
        try {
            Map<String, Object> doc = new HashMap<>();
            doc.put("chunk_id", chunkId);
            doc.put("tenant_id", tenantId);
            doc.put("kb_id", kbId);
            doc.put("status", "PUBLISHED");
            doc.put("content", content);
            Request request = new Request("PUT", "/" + index + "/_doc/" + chunkId);
            request.setJsonEntity(objectMapper.writeValueAsString(doc));
            client.performRequest(request);
        } catch (Exception e) {
            throw new RuntimeException("ES 写入失败: " + e.getMessage(), e);
        }
    }

    /**
     * 批量写入 BM25 文档(_bulk, 一次网络往返; P2-19 替代逐条 PUT)
     *
     * @param items 每项为 [chunkId, tenantId, kbId, content]
     */
    public void insertChunks(List<Object[]> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        try {
            // _bulk NDJSON: 每行 {index} action + 每行文档体
            StringBuilder bulk = new StringBuilder();
            for (Object[] item : items) {
                Long chunkId = (Long) item[0];
                Long tenantId = (Long) item[1];
                Long kbId = (Long) item[2];
                String content = (String) item[3];
                bulk.append("{\"index\":{\"_index\":\"").append(index).append("\",\"_id\":\"").append(chunkId).append("\"}}\n");
                Map<String, Object> doc = new HashMap<>();
                doc.put("chunk_id", chunkId);
                doc.put("tenant_id", tenantId);
                doc.put("kb_id", kbId);
                doc.put("status", "PUBLISHED");
                doc.put("content", content);
                bulk.append(objectMapper.writeValueAsString(doc)).append("\n");
            }
            Request request = new Request("POST", "/_bulk");
            request.setEntity(new StringEntity(bulk.toString(), ContentType.create("application/x-ndjson", StandardCharsets.UTF_8)));
            client.performRequest(request);
        } catch (Exception e) {
            throw new RuntimeException("ES 批量写入失败: " + e.getMessage(), e);
        }
    }

    /**
     * 按片段 id 批量删除 ES 文档
     *
     * @param chunkIds 片段 id(ES 文档 _id = chunkId)
     */
    public void deleteChunks(List<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        try {
            // 批量删除: 构造 _bulk 请求, 每条 delete by id(NDJSON, 每行一个 action, 末尾换行)
            StringBuilder bulk = new StringBuilder();
            for (Long id : chunkIds) {
                bulk.append("{\"delete\":{\"_index\":\"").append(index).append("\",\"_id\":\"").append(id).append("\"}}\n");
            }
            Request request = new Request("POST", "/_bulk");
            // _bulk 需要 application/x-ndjson(不能直接用 setJsonEntity 的 application/json)
            request.setEntity(new StringEntity(bulk.toString(), ContentType.create("application/x-ndjson", StandardCharsets.UTF_8)));
            client.performRequest(request);
        } catch (Exception e) {
            throw new RuntimeException("ES 批量删除失败: " + e.getMessage(), e);
        }
    }

}
