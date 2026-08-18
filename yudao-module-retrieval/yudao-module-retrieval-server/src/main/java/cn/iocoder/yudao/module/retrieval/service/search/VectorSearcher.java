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
import java.util.stream.Collectors;
import java.util.Map;

/**
 * 向量检索(Milvus ai_chunk_vector; 无 status 标量, 状态过滤在融合后)
 * <p>
 * 客户端自建(参考 ingestion MilvusChunkStore)。集合由入库管线创建, 向量字段名为 embedding
 * (已按线上集合 schema 核实), 若外部重建集合改名可经配置覆盖。
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

    @PostConstruct
    public void init() {
        try {
            client = new MilvusServiceClient(ConnectParam.newBuilder()
                    .withHost(host)
                    .withPort(port)
                    .build());
        } catch (Exception e) {
            // 连接失败不阻断启动, 后续检索会因 client 为空而走降级(记日志)
            log.error("[init][Milvus 客户端初始化失败: {}:{}]", host, port, e);
        }
    }

    /**
     * 向量检索(多条向量合并召回, 同 chunk 取最高分)
     *
     * @param vectors 查询向量列表(每个检索变体一个)
     * @param tenantId 租户编号
     * @param kbIds 知识库编号(空=不限)
     * @param topK 每条向量返回条数
     * @return [chunkId, score] 列表(score 已换算为 1-similarity, 单调递增即相关); 失败返回空列表
     */
    public List<Map.Entry<Long, Double>> search(List<List<Float>> vectors, Long tenantId, List<Long> kbIds, int topK) {
        if (client == null) {
            log.error("[search][Milvus client 未初始化, 返回空]");
            return List.of();
        }
        try {
            // 确保集合已加载(幂等)
            R<RpcStatus> loadResp = client.loadCollection(LoadCollectionParam.newBuilder()
                    .withCollectionName(collection).build());
            if (loadResp.getStatus() != R.Status.Success.getCode()) {
                log.error("[search][集合 {} 加载失败: {}]", collection, loadResp.getMessage());
            }
            // 标量过滤表达式: 租户必选, 知识库可选
            StringBuilder expr = new StringBuilder("tenant_id == ").append(tenantId);
            if (kbIds != null && !kbIds.isEmpty()) {
                expr.append(" && kb_id in [");
                for (int i = 0; i < kbIds.size(); i++) {
                    if (i > 0) {
                        expr.append(",");
                    }
                    expr.append(kbIds.get(i));
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
                return List.of();
            }
            // 结果按每条查询向量分组; 合并时同 chunk 保留最相似的一条
            SearchResultData data = resp.getData().getResults();
            SearchResultsWrapper wrapper = new SearchResultsWrapper(data);
            Map<Long, Double> best = new HashMap<>();
            for (int i = 0; i < vectors.size(); i++) {
                List<SearchResultsWrapper.IDScore> scores = wrapper.getIDScore(i);
                for (SearchResultsWrapper.IDScore s : scores) {
                    double similarity = s.getScore(); // COSINE: 越大越相似
                    // 转"相关性"单调值; 合并取最相似(即该值最小)
                    double score = 1 - similarity;
                    best.merge(s.getLongID(), score, Math::min);
                }
            }
            // 返回按相关性降序(score 小=相似度高), RRF 依赖有序输入
            return best.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("[vector][检索失败]", e);
            return List.of();
        }
    }

}
