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
 * 通用 BM25 检索(ES ai_chunk_bm25)。
 *
 * <p>本类只执行文本检索和显式 hard scope，不解析专利/合同/法规标识；领域范围收敛由 RetrievalScopePlugin 完成。</p>
 */
@Slf4j
@Service
public class Bm25Searcher {

    @Value("${spring.elasticsearch.uris:http://127.0.0.1:9200}") private String uris;
    @Value("${yudao.ai.es.index:ai_chunk_bm25}") private String index;

    private RestClient client;

    /** 带 ES 真实总命中数的检索结果，避免将 TopK 返回条数误当全集数量。 */
    public record SearchHits(List<Map.Entry<Long, Double>> hits, long totalHits) {
        public SearchHits {
            hits = hits == null ? List.of() : List.copyOf(hits);
        }
        public static SearchHits empty() { return new SearchHits(List.of(), 0L); }
    }

    /** 插件执行使用的普通 BM25 结果；显式区分“正常零命中”和“ES 执行失败”。 */
    public record SearchExecution(List<Map.Entry<Long, Double>> hits, boolean failed, String errorMessage) {
        public SearchExecution {
            hits = hits == null ? List.of() : List.copyOf(hits);
        }
        public static SearchExecution success(List<Map.Entry<Long, Double>> hits) {
            return new SearchExecution(hits, false, null);
        }
        public static SearchExecution failure(String message) {
            return new SearchExecution(List.of(), true, message);
        }
    }

    /** 精确短语执行结果；保留 totalHits，同时显式区分 ES 故障与权威零命中。 */
    public record ExactSearchExecution(SearchHits searchHits, boolean failed, String errorMessage) {
        public ExactSearchExecution {
            searchHits = searchHits == null ? SearchHits.empty() : searchHits;
        }
        public static ExactSearchExecution success(SearchHits hits) {
            return new ExactSearchExecution(hits, false, null);
        }
        public static ExactSearchExecution failure(String message) {
            return new ExactSearchExecution(SearchHits.empty(), true, message);
        }
    }

    @PostConstruct
    public void init() {
        try {
            String uri = uris.split(",")[0].trim();
            String scheme = uri.startsWith("https") ? "https" : "http";
            String rest = uri.replaceAll("^https?://", "");
            int slash = rest.indexOf('/');
            if (slash >= 0) rest = rest.substring(0, slash);
            String host;
            int port;
            if (rest.startsWith("[")) {
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
            log.error("[init][ES uris 解析失败, 跳过初始化: {}]", uris, e);
        }
    }

    public List<Map.Entry<Long, Double>> search(String query, Long tenantId, List<Long> kbIds, int topK) {
        return search(query, tenantId, kbIds, topK, null);
    }

    /** 旧调用保持 List 语义；新 Recall 插件应使用 searchWithStatus 区分基础设施失败。 */
    public List<Map.Entry<Long, Double>> search(String query, Long tenantId, List<Long> kbIds, int topK,
                                                List<Long> documentIds) {
        return searchWithStatus(query, tenantId, kbIds, topK, documentIds).hits();
    }

    public SearchExecution searchWithStatus(String query, Long tenantId, List<Long> kbIds, int topK,
                                            List<Long> documentIds) {
        if (client == null) return SearchExecution.failure("Elasticsearch client is not initialized");
        try {
            List<Map<String, Object>> filter = baseFilters(tenantId, kbIds);
            if (documentIds != null && !documentIds.isEmpty()) {
                filter.add(Map.of("terms", Map.of("document_id", documentIds)));
            }
            Map<String, Object> bool = new HashMap<>();
            bool.put("must", List.of(Map.of("match", Map.of("content", Map.of("query", query, "analyzer", "ik_smart")))));
            bool.put("filter", filter);
            return SearchExecution.success(execute(Map.of(
                    "query", Map.of("bool", bool), "size", topK, "track_scores", true)).hits());
        } catch (Exception e) {
            log.error("[bm25][检索失败, query={}]", query, e);
            return SearchExecution.failure(e.getClass().getSimpleName() + ": " + safeMessage(e.getMessage()));
        }
    }

    /** 兼容旧调用：仅返回 TopK hits。需要完整性判断时应调用 searchExactPhraseWithTotal。 */
    public List<Map.Entry<Long, Double>> searchExactPhrase(String phrase, Long tenantId, List<Long> kbIds,
                                                           int topK, List<Long> documentIds) {
        return searchExactPhraseWithTotal(phrase, tenantId, kbIds, topK, documentIds).hits();
    }

    /**
     * 兼容旧调用：返回 SearchHits；ES 失败仍退化为空。新的 ExactText 主链必须使用
     * {@link #searchExactPhraseWithStatus(String, Long, List, int, List)} 保留失败语义。
     */
    public SearchHits searchExactPhraseWithTotal(String phrase, Long tenantId, List<Long> kbIds,
                                                 int topK, List<Long> documentIds) {
        return searchExactPhraseWithStatus(phrase, tenantId, kbIds, topK, documentIds).searchHits();
    }

    /**
     * EXACT_TEXT_SEARCH：match_phrase + track_total_hits，并显式返回基础设施状态。
     */
    public ExactSearchExecution searchExactPhraseWithStatus(String phrase, Long tenantId, List<Long> kbIds,
                                                            int topK, List<Long> documentIds) {
        if (phrase == null || phrase.isBlank()) return ExactSearchExecution.success(SearchHits.empty());
        if (client == null) return ExactSearchExecution.failure("Elasticsearch client is not initialized");
        try {
            List<Map<String, Object>> filter = baseFilters(tenantId, kbIds);
            if (documentIds != null && !documentIds.isEmpty()) {
                filter.add(Map.of("terms", Map.of("document_id", documentIds)));
            }
            Map<String, Object> bool = new HashMap<>();
            bool.put("must", List.of(Map.of("match_phrase", Map.of("content", Map.of("query", phrase, "slop", 0)))));
            bool.put("filter", filter);
            return ExactSearchExecution.success(execute(Map.of(
                    "query", Map.of("bool", bool),
                    "size", topK,
                    "track_scores", true,
                    "track_total_hits", true)));
        } catch (Exception e) {
            log.error("[searchExactPhraseWithStatus][精确短语检索失败, phrase={}]", phrase, e);
            return ExactSearchExecution.failure(e.getClass().getSimpleName() + ": " + safeMessage(e.getMessage()));
        }
    }

    /**
     * 显式文档范围内返回片段，不再自行解析任何领域标识。
     * 调用方必须先通过 Scope Pipeline 得到 documentIds。
     */
    public List<Map.Entry<Long, Double>> searchExactDocument(String query, Long tenantId, List<Long> kbIds,
                                                             int topK, List<Long> documentIds) {
        if (client == null || documentIds == null || documentIds.isEmpty()) return List.of();
        try {
            List<Map<String, Object>> filter = baseFilters(tenantId, kbIds);
            filter.add(Map.of("terms", Map.of("document_id", documentIds)));
            Map<String, Object> bool = new HashMap<>();
            bool.put("filter", filter);
            return execute(Map.of("query", Map.of("bool", bool), "size", topK, "track_scores", false)).hits();
        } catch (Exception e) {
            log.error("[searchExactDocument][显式文档范围检索失败, query={}]", query, e);
            return List.of();
        }
    }

    /** 兼容旧签名；没有显式 documentIds 时 fail-closed，不再暗含专利解析。 */
    public List<Map.Entry<Long, Double>> searchExactDocument(String query, Long tenantId, List<Long> kbIds, int topK) {
        return List.of();
    }

    private List<Map<String, Object>> baseFilters(Long tenantId, List<Long> kbIds) {
        List<Map<String, Object>> filter = new ArrayList<>();
        if (tenantId != null) filter.add(Map.of("term", Map.of("tenant_id", tenantId)));
        filter.add(Map.of("term", Map.of("status", "PUBLISHED")));
        if (kbIds != null && !kbIds.isEmpty()) filter.add(Map.of("terms", Map.of("kb_id", kbIds)));
        return filter;
    }

    private SearchHits execute(Map<String, Object> body) throws Exception {
        Request request = new Request("POST", "/" + index + "/_search");
        request.setJsonEntity(JSONUtil.toJsonStr(body));
        Response response = client.performRequest(request);
        JSONObject resp = JSONUtil.parseObj(new String(response.getEntity().getContent().readAllBytes()));
        JSONObject hitsObject = resp.getJSONObject("hits");
        JSONArray hits = hitsObject.getJSONArray("hits");
        long total = extractTotalHits(hitsObject, hits == null ? 0 : hits.size());
        List<Map.Entry<Long, Double>> result = new ArrayList<>();
        if (hits != null) {
            for (Object o : hits) {
                JSONObject hit = (JSONObject) o;
                result.add(Map.entry(hit.getJSONObject("_source").getLong("chunk_id"), hit.getDouble("_score", 1D)));
            }
        }
        return new SearchHits(result, total);
    }

    /** ES7/8 hits.total 是对象(value/relation)，兼容极少数旧形态数值。 */
    private long extractTotalHits(JSONObject hitsObject, int fallback) {
        if (hitsObject == null) return fallback;
        Object total = hitsObject.get("total");
        if (total instanceof Number number) return number.longValue();
        if (total instanceof JSONObject object) {
            Long value = object.getLong("value");
            return value != null ? value : fallback;
        }
        if (total instanceof Map<?, ?> map) {
            Object value = map.get("value");
            if (value instanceof Number number) return number.longValue();
        }
        return fallback;
    }

    private String safeMessage(String message) {
        if (message == null) return "";
        return message.length() <= 300 ? message : message.substring(0, 300);
    }
}
