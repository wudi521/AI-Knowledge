package cn.iocoder.yudao.module.retrieval.service.search;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import cn.iocoder.yudao.module.knowledge.api.dto.PatentDocumentLookupReqDTO;
import cn.iocoder.yudao.module.retrieval.service.domain.PatentQueryPreParser;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
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

/** BM25 检索(ES ai_chunk_bm25)。明确专利编号时先解析 documentId 并做 ES 硬过滤。 */
@Slf4j
@Service
public class Bm25Searcher {

    @Value("${spring.elasticsearch.uris:http://127.0.0.1:9200}") private String uris;
    @Value("${yudao.ai.es.index:ai_chunk_bm25}") private String index;

    @Resource private KnowledgeApi knowledgeApi;
    @Resource private PatentQueryPreParser patentQueryPreParser;

    private RestClient client;

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
        if (client == null) return List.of();
        try {
            List<Map<String, Object>> filter = baseFilters(tenantId, kbIds);
            List<Long> patentDocumentIds = resolvePatentDocumentIds(query, kbIds);
            if (patentDocumentIds != null) {
                if (patentDocumentIds.isEmpty()) {
                    log.info("[search][专利精确标识未定位到文档, BM25 fail-closed 返回空: query={}]", query);
                    return List.of();
                }
                filter.add(Map.of("terms", Map.of("document_id", patentDocumentIds)));
            }

            Map<String, Object> bool = new HashMap<>();
            bool.put("must", List.of(Map.of("match", Map.of("content", Map.of("query", query, "analyzer", "ik_smart")))));
            bool.put("filter", filter);
            return execute(Map.of("query", Map.of("bool", bool), "size", topK, "track_scores", true));
        } catch (Exception e) {
            log.error("[bm25][检索失败, query={}]", query, e);
            return List.of();
        }
    }

    /**
     * EXACT_METADATA 快路径：已知专利编号时，不再要求 query 文本命中，直接在精确 documentId 内取已发布 chunk。
     * chunk metadata 已携带整份专利的结构化著录字段，可作为 [C1] 来源锚点。
     */
    public List<Map.Entry<Long, Double>> searchExactDocument(String query, Long tenantId, List<Long> kbIds, int topK) {
        if (client == null) return List.of();
        try {
            List<Long> documentIds = resolvePatentDocumentIds(query, kbIds);
            if (documentIds == null || documentIds.isEmpty()) {
                log.info("[searchExactDocument][未定位专利文档, fail-closed: query={}]", query);
                return List.of();
            }
            List<Map<String, Object>> filter = baseFilters(tenantId, kbIds);
            filter.add(Map.of("terms", Map.of("document_id", documentIds)));
            Map<String, Object> bool = new HashMap<>();
            bool.put("filter", filter);
            return execute(Map.of("query", Map.of("bool", bool), "size", topK, "track_scores", false));
        } catch (Exception e) {
            log.error("[searchExactDocument][精确专利文档检索失败, query={}]", query, e);
            return List.of();
        }
    }

    private List<Map<String, Object>> baseFilters(Long tenantId, List<Long> kbIds) {
        List<Map<String, Object>> filter = new ArrayList<>();
        filter.add(Map.of("term", Map.of("tenant_id", tenantId)));
        filter.add(Map.of("term", Map.of("status", "PUBLISHED")));
        if (kbIds != null && !kbIds.isEmpty()) filter.add(Map.of("terms", Map.of("kb_id", kbIds)));
        return filter;
    }

    private List<Map.Entry<Long, Double>> execute(Map<String, Object> body) throws Exception {
        Request request = new Request("POST", "/" + index + "/_search");
        request.setJsonEntity(JSONUtil.toJsonStr(body));
        Response response = client.performRequest(request);
        JSONObject resp = JSONUtil.parseObj(new String(response.getEntity().getContent().readAllBytes()));
        JSONArray hits = resp.getJSONObject("hits").getJSONArray("hits");
        List<Map.Entry<Long, Double>> result = new ArrayList<>();
        for (Object o : hits) {
            JSONObject hit = (JSONObject) o;
            result.add(Map.entry(hit.getJSONObject("_source").getLong("chunk_id"), hit.getDouble("_score", 1D)));
        }
        return result;
    }

    /**
     * 返回 null 表示不是带精确专利标识的查询；返回空集合表示是精确查询但当前 KB 内没有对应文档。
     */
    private List<Long> resolvePatentDocumentIds(String query, List<Long> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) return null;
        PatentQueryPreParser.PatentQueryHints hints = patentQueryPreParser.parse(query);
        if (hints == null || !hints.hasExactDocumentIdentifier()) return null;
        try {
            PatentDocumentLookupReqDTO req = new PatentDocumentLookupReqDTO();
            req.setKbIds(kbIds);
            req.setApplicationNo(hints.getApplicationNo());
            req.setPublicationNo(hints.getPublicationNo());
            List<Long> ids = knowledgeApi.lookupPatentDocuments(req).getCheckedData();
            return ids == null ? List.of() : ids;
        } catch (Exception e) {
            // 精确查询的定位 RPC 失败时不应退化为全库搜索，避免跨专利污染。
            log.warn("[resolvePatentDocumentIds][专利文档定位失败, fail-closed: {}]", e.getMessage());
            return List.of();
        }
    }
}
