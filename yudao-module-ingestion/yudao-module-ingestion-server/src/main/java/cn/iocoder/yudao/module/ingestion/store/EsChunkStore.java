package cn.iocoder.yudao.module.ingestion.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
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
        String host = uris.replaceAll("^https?://", "").split(":")[0];
        int port = Integer.parseInt(uris.replaceAll("^https?://", "").split(":")[1].replaceAll("/.*", ""));
        client = RestClient.builder(new HttpHost(host, port, "http")).build();
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
                    "chunk_id", Map.of("type", "keyword"),
                    "tenant_id", Map.of("type", "keyword"),
                    "kb_id", Map.of("type", "keyword"),
                    "status", Map.of("type", "keyword"),
                    "content", Map.of("type", "text")
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
            doc.put("chunk_id", String.valueOf(chunkId));
            doc.put("tenant_id", String.valueOf(tenantId));
            doc.put("kb_id", String.valueOf(kbId));
            doc.put("status", "PUBLISHED");
            doc.put("content", content);
            Request request = new Request("PUT", "/" + index + "/_doc/" + chunkId);
            request.setJsonEntity(objectMapper.writeValueAsString(doc));
            client.performRequest(request);
        } catch (Exception e) {
            throw new RuntimeException("ES 写入失败: " + e.getMessage(), e);
        }
    }

}
