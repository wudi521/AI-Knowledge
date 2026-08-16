package cn.iocoder.yudao.module.ingestion.store;

import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.grpc.DataType;
import io.milvus.grpc.MutationResult;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.index.CreateIndexParam;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Milvus 向量写入
 */
@Slf4j
@Component
public class MilvusChunkStore {

    @Value("${yudao.ai.milvus.host:127.0.0.1}")
    private String host;
    @Value("${yudao.ai.milvus.port:19530}")
    private Integer port;
    @Value("${yudao.ai.milvus.collection:ai_chunk_vector}")
    private String collection;
    @Value("${yudao.ai.milvus.dim:1024}")
    private Integer dim;

    private MilvusServiceClient client;

    @PostConstruct
    public void init() {
        client = new MilvusServiceClient(ConnectParam.newBuilder()
                .withHost(host)
                .withPort(port)
                .build());
        createCollectionIfAbsent();
        // 无论集合是否新建都加载: Milvus 重启后已有集合可能处于未加载状态, 插入会报错
        client.loadCollection(LoadCollectionParam.newBuilder()
                .withCollectionName(collection).build());
        log.info("[init][Milvus 集合 {} 加载完成]", collection);
    }

    private void createCollectionIfAbsent() {
        R<Boolean> has = client.hasCollection(HasCollectionParam.newBuilder()
                .withCollectionName(collection).build());
        if (has.getData() != null && has.getData()) {
            return;
        }
        FieldType chunkId = FieldType.newBuilder()
                .withName("chunk_id").withDataType(DataType.Int64).withPrimaryKey(true).build();
        FieldType embedding = FieldType.newBuilder()
                .withName("embedding").withDataType(DataType.FloatVector).withDimension(dim).build();
        FieldType tenantId = FieldType.newBuilder()
                .withName("tenant_id").withDataType(DataType.Int64).build();
        FieldType kbId = FieldType.newBuilder()
                .withName("kb_id").withDataType(DataType.Int64).build();
        CreateCollectionParam param = CreateCollectionParam.newBuilder()
                .withCollectionName(collection)
                .withFieldTypes(List.of(chunkId, embedding, tenantId, kbId))
                .withConsistencyLevel(ConsistencyLevelEnum.STRONG)
                .build();
        client.createCollection(param);
        // 建索引
        client.createIndex(CreateIndexParam.newBuilder()
                .withCollectionName(collection)
                .withFieldName("embedding")
                .withIndexType(IndexType.IVF_FLAT)
                .withMetricType(MetricType.COSINE)
                .withExtraParam("{\"nlist\":1024}")
                .build());
        log.info("[init][Milvus 集合 {} 创建完成]", collection);
    }

    /**
     * 批量插入向量
     *
     * @param chunkIds 片段 id(与 MySQL 对应)
     * @param vectors 向量列表
     * @param tenantId 租户
     * @param kbId 知识库
     */
    public void insertVectors(List<Long> chunkIds, List<List<Float>> vectors, Long tenantId, Long kbId) {
        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field("chunk_id", chunkIds));
        fields.add(new InsertParam.Field("embedding", vectors));
        fields.add(new InsertParam.Field("tenant_id", chunkIds.stream().map(x -> tenantId).toList()));
        fields.add(new InsertParam.Field("kb_id", chunkIds.stream().map(x -> kbId).toList()));
        InsertParam param = InsertParam.newBuilder()
                .withCollectionName(collection)
                .withFields(fields)
                .build();
        R<MutationResult> insertResp = client.insert(param);
        if (insertResp.getStatus() != R.Status.Success.getCode()) {
            throw new RuntimeException("Milvus 写入失败: " + insertResp.getMessage());
        }
    }

}
