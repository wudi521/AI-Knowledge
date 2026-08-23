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
            Map<String, Object> body = new HashMap<>();
            Map<String, Object> bool = new HashMap<>();
            List<Map<String, Object>> must = new ArrayList<>();
            must.add(Map.of("match", Map.of("content", Map.of("query", query, "analyzer", "ik_smart"))));

            List<Map<String, Object>> filter = new ArrayList<>();
            filter.add(Map.of("term", Map.of("tenant_id", tenantId)));
            filter.add(Map.of("term", Map.of("status", "PUBLISHED")));
            if (kbIds != null && !kbIds.isEmpty()) filter.add(Map.of("terms", Map.of("kb_id", kbIds)));

            List<Long> patentDocumentIds = resolvePatentDocumentIds(query, kbIds);
            if (patentDocumentIds != null) {
                if (patentDocumentIds.isEmpty()) {
                    log.info("[search][专利精确标识未定位到文档, BM25 fail-closed 返回空: query={}]", query);
                    return List.of();
                }
                filter.add(Map.of("terms", Map.of("document_id", patentDocumentIds)));
            }

            bool.put("must", must);
            bool.put("filter", filter);
            body.put("query", Map.of("bool", bool));
            body.put("size", topK);
            body.put("track_scores", true);

            Request request = new Request("POST", "/" + index + "/_search");
            request.setJsonEntity(JSONUtil.toJsonStr(body));
            Response response = client.performRequest(request);
            JSONObject resp = JSONUtil.parseObj(new String(response.getEntity().getContent().readAllBytes()));
            JSONArray hits = resp.getJSONObject("hits").getJSONArray("hits");
            List<Map.Entry<Long, Double>> result = new ArrayList<>();
            for (Object o : hits) {
                JSONObject hit = (JSONObject) o;
                result.add(Map.entry(hit.getJSONObject("_source").getLong("chunk_id"), hit.getDouble("_score", 0D)));
            }
            return result;
        } catch (Exception e) {
            log.error("[bm25][检索失败, query={}]", query, e);
            return List.of();
        }
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
