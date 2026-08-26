package cn.iocoder.yudao.module.retrieval.service.search;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.SearchResultData;
import io.milvus.param.ConnectParam;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.SearchResultsWrapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 向量检索(Milvus ai_chunk_vector; 无 status 标量, 状态过滤在融合后)。
 *
 * <p>Searcher 保持领域无关；插件调用 searchWithStatus 显式区分正常零命中与 Milvus/Schema 能力降级。</p>
 */
@Slf4j
@Service
public class VectorSearcher {

    @Value("${yudao.ai.milvus.host:127.0.0.1}")
    private String host;
    @Value("${yudao.ai.milvus.port:19530}")
    private Integer port;
    @Value("${yudao.ai.milvus.collection:ai_chunk_vector}")
    private String collection;
    @Value("${yudao.ai.milvus.vector-field:embedding}")
    private String vectorField;

    private MilvusServiceClient client;

    /** 当前集合已存在的标量字段名(SCOPED Vector 硬过滤前置检测)。 */
    private java.util.Set<String> scalarFields = java.util.Set.of();

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

    @PostConstruct
    public void init() {
        try {
            client = new MilvusServiceClient(ConnectParam.newBuilder()
                    .withHost(host)
                    .withPort(port)
                    .build());
            scalarFields = describeFields();
        } catch (Exception e) {
            log.error("[init][Milvus 客户端初始化失败: {}:{}]", host, port, e);
        }
    }

    private java.util.Set<String> describeFields() {
        try {
            io.milvus.param.R<io.milvus.grpc.DescribeCollectionResponse> resp = client.describeCollection(
                    io.milvus.param.collection.DescribeCollectionParam.newBuilder()
                            .withCollectionName(collection).build());
            if (resp.getStatus() != io.milvus.param.R.Status.Success.getCode()) {
                log.warn("[describeFields][集合 {} 描述失败: {}]", collection, resp.getMessage());
                return java.util.Set.of();
            }
            java.util.Set<String> fields = new java.util.HashSet<>();
            for (io.milvus.grpc.FieldSchema schema : resp.getData().getSchema().getFieldsList()) {
                if (schema != null && schema.getName() != null) fields.add(schema.getName());
            }
            log.info("[describeFields][集合 {} 标量字段: {}]", collection, fields);
            return java.util.Set.copyOf(fields);
        } catch (Exception e) {
            log.warn("[describeFields][字段描述失败, 回退空: {}]", e.getMessage());
            return java.util.Set.of();
        }
    }

    public List<Map.Entry<Long, Double>> search(List<List<Float>> vectors, Long tenantId, List<Long> kbIds, int topK) {
        return search(vectors, tenantId, kbIds, topK, null);
    }

    /** 旧调用保留 List 语义；新 Recall 插件应使用 searchWithStatus。 */
    public List<Map.Entry<Long, Double>> search(List<List<Float>> vectors, Long tenantId, List<Long> kbIds, int topK,
                                                List<Long> documentIds) {
        return searchWithStatus(vectors, tenantId, kbIds, topK, documentIds).hits();
    }

    public SearchExecution searchWithStatus(List<List<Float>> vectors, Long tenantId, List<Long> kbIds, int topK,
                                            List<Long> documentIds) {
        if (client == null) {
            log.error("[search][Milvus client 未初始化]");
            return SearchExecution.failure("Milvus client is not initialized");
        }
        if (vectors == null || vectors.isEmpty()) return SearchExecution.success(List.of());
        try {
            R<RpcStatus> loadResp = client.loadCollection(LoadCollectionParam.newBuilder()
                    .withCollectionName(collection).build());
            if (loadResp.getStatus() != R.Status.Success.getCode()) {
                log.error("[search][集合 {} 加载失败: {}]", collection, loadResp.getMessage());
                return SearchExecution.failure("Milvus collection load failed: " + safeMessage(loadResp.getMessage()));
            }
            StringBuilder expr = new StringBuilder("tenant_id == ").append(tenantId);
            if (kbIds != null && !kbIds.isEmpty()) {
                expr.append(" && kb_id in [");
                for (int i = 0; i < kbIds.size(); i++) {
                    if (i > 0) expr.append(",");
                    expr.append(kbIds.get(i));
                }
                expr.append("]");
            }
            if (documentIds != null && !documentIds.isEmpty()) {
                if (!scalarFields.contains("document_id")) {
                    log.warn("[search][SCOPED Vector 需要 document_id 过滤但集合 {} 缺该字段]", collection);
                    return SearchExecution.failure("Milvus schema lacks document_id for hard-scoped vector retrieval");
                }
                expr.append(" && document_id in [");
                for (int i = 0; i < documentIds.size(); i++) {
                    if (i > 0) expr.append(",");
                    expr.append(documentIds.get(i));
                }
                expr.append("]");
            }
            SearchParam param = SearchParam.newBuilder()
                    .withCollectionName(collection)
                    .withFloatVectors(vectors)
                    .withVectorFieldName(vectorField)
                    .withExpr(expr.toString())
                    .withTopK(topK)
                    .withMetricType(MetricType.COSINE)
                    .withOutFields(List.of("chunk_id"))
                    .build();
            R<io.milvus.grpc.SearchResults> resp = client.search(param);
            if (resp.getStatus() != R.Status.Success.getCode()) {
                log.error("[search][Milvus 检索失败: {}]", resp.getMessage());
                return SearchExecution.failure("Milvus search failed: " + safeMessage(resp.getMessage()));
            }
            SearchResultData data = resp.getData().getResults();
            SearchResultsWrapper wrapper = new SearchResultsWrapper(data);
            Map<Long, Double> best = new HashMap<>();
            for (int i = 0; i < vectors.size(); i++) {
                List<SearchResultsWrapper.IDScore> scores = wrapper.getIDScore(i);
                for (SearchResultsWrapper.IDScore s : scores) {
                    double similarity = s.getScore();
                    double score = 1 - similarity;
                    best.merge(s.getLongID(), score, Math::min);
                }
            }
            return SearchExecution.success(best.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue())
                    .collect(Collectors.toList()));
        } catch (Exception e) {
            log.error("[vector][检索失败]", e);
            return SearchExecution.failure(e.getClass().getSimpleName() + ": " + safeMessage(e.getMessage()));
        }
    }

    private String safeMessage(String message) {
        if (message == null) return "";
        return message.length() <= 300 ? message : message.substring(0, 300);
    }
}
