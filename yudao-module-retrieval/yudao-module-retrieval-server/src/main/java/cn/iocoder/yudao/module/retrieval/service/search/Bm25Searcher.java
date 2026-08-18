package cn.iocoder.yudao.module.retrieval.service.search;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BM25 检索(ES ai_chunk_bm25, ik_smart 搜索; 仅已发布; 租户/知识库过滤)
 * <p>
 * 客户端自建(参考 ingestion EsChunkStore), 解析失败不阻断启动, 检索时降级返回空
 */
@Slf4j
@Service
public class Bm25Searcher {

    @Value("${spring.elasticsearch.uris:http://127.0.0.1:9200}")
    private String uris;
    @Value("${yudao.ai.es.index:ai_chunk_bm25}")
    private String index;

    /** 低层 RestClient(懒初始化, 失败不阻断启动) */
    private RestClient client;

    @PostConstruct
    public void init() {
        try {
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
        } catch (Exception e) {
            // 解析失败不阻断启动, 后续检索会因 client 为空而走降级(记日志)
            log.error("[init][ES uris 解析失败, 跳过初始化: {}]", uris, e);
        }
    }

    /**
     * BM25 检索
     *
     * @param query 查询文本
     * @param tenantId 租户编号
     * @param kbIds 知识库编号(空=不限)
     * @param topK 返回条数
     * @return [chunkId, score] 列表, 已按分降序; 失败返回空列表
     */
    public List<Map.Entry<Long, Double>> search(String query, Long tenantId, List<Long> kbIds, int topK) {
        if (client == null) {
            log.error("[search][ES client 未初始化, 返回空]");
            return List.of();
        }
        try {
            Map<String, Object> body = new HashMap<>();
            Map<String, Object> bool = new HashMap<>();
            // must: content 匹配(ik_smart 搜索分词)
            List<Map<String, Object>> must = new ArrayList<>();
            must.add(Map.of("match", Map.of("content", Map.of("query", query, "analyzer", "ik_smart"))));
            // filter: 租户 + 已发布 + 知识库范围
            List<Map<String, Object>> filter = new ArrayList<>();
            filter.add(Map.of("term", Map.of("tenant_id", tenantId)));
            filter.add(Map.of("term", Map.of("status", "PUBLISHED")));
            if (kbIds != null && !kbIds.isEmpty()) {
                filter.add(Map.of("terms", Map.of("kb_id", kbIds)));
            }
            bool.put("must", must);
            bool.put("filter", filter);
            body.put("query", Map.of("bool", bool));
            body.put("size", topK);
            body.put("track_scores", true); // filter 不参与打分, 需显式保留 must 的 _score

            Request request = new Request("POST", "/" + index + "/_search");
            request.setJsonEntity(JSONUtil.toJsonStr(body));
            Response response = client.performRequest(request);
            JSONObject resp = JSONUtil.parseObj(new String(response.getEntity().getContent().readAllBytes()));
            JSONArray hits = resp.getJSONObject("hits").getJSONArray("hits");

            List<Map.Entry<Long, Double>> result = new ArrayList<>();
            for (Object o : hits) {
                JSONObject hit = (JSONObject) o;
                long chunkId = hit.getJSONObject("_source").getLong("chunk_id");
                double score = hit.getDouble("_score", 0D);
                result.add(Map.entry(chunkId, score));
            }
            // ES 默认已按 _score 降序返回
            return result;
        } catch (Exception e) {
            log.error("[bm25][检索失败, query={}]", query, e);
            return List.of();
        }
    }

}
