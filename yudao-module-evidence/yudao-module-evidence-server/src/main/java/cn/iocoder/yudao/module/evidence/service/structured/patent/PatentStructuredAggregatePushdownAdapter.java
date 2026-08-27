package cn.iocoder.yudao.module.evidence.service.structured.patent;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.evidence.service.structured.core.DataGrain;
import cn.iocoder.yudao.module.evidence.service.structured.core.Operation;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredAggregateSpec;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredPipelinePlan;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredPipelineResult;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredPushdownAdapter;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredPushdownResult;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredValueExpression;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredValueTransform;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeStructuredAggregateApi;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeStructuredOrderApi;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeStructuredPageApi;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredAggregateReqDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredAggregateRespDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredOrderReqDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredOrderRespDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryReqDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryRespDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryRowDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Patent Domain 的权威整计划聚合下推。
 *
 * <p>当前支持两类已经有存储层完整性证明的 typed 运算：</p>
 * <ul>
 *   <li>DOCUMENT_COUNT / PATENT_COUNT 的 COUNT；</li>
 *   <li>TITLE -> LENGTH 的 MIN / MAX，复用权威 ORDER proof，而不是重新扫描 JVM 全集。</li>
 * </ul>
 *
 * <p>这里判断的是结构化算子合同，不读取 originalGoal，也不匹配“最长/最短/第一”等自然语言。</p>
 */
@Component
public class PatentStructuredAggregatePushdownAdapter implements StructuredPushdownAdapter {

    private static final Set<String> COUNT_METRICS = Set.of(
            PatentStructuredPack.METRIC_DOCUMENT_COUNT,
            PatentStructuredPack.METRIC_PATENT_COUNT);

    private final KnowledgeStructuredAggregateApi aggregateApi;
    private final KnowledgeStructuredOrderApi orderApi;
    private final KnowledgeStructuredPageApi pageApi;

    @Autowired
    public PatentStructuredAggregatePushdownAdapter(KnowledgeStructuredAggregateApi aggregateApi,
                                                    KnowledgeStructuredOrderApi orderApi,
                                                    KnowledgeStructuredPageApi pageApi) {
        this.aggregateApi = aggregateApi;
        this.orderApi = orderApi;
        this.pageApi = pageApi;
    }

    /** 兼容既有 COUNT 单元测试。 */
    public PatentStructuredAggregatePushdownAdapter(KnowledgeStructuredAggregateApi aggregateApi) {
        this(aggregateApi, null, null);
    }

    @Override
    public String domainCode() {
        return PatentStructuredPack.DOMAIN_CODE;
    }

    @Override
    public boolean supports(StructuredPipelinePlan plan) {
        if (plan == null || plan.getScope() == null || plan.getScope().getCurrentKbId() == null) return false;
        if (!PatentStructuredPack.DOMAIN_CODE.equalsIgnoreCase(plan.getDomainCode())) return false;
        if (!PatentStructuredPack.ENTITY_PATENT_DOCUMENT.equals(plan.getEntityType())) return false;
        if (plan.getFilter() != null || plan.isDistinct()) return false;
        if (plan.getGroupBy() != null && !plan.getGroupBy().isEmpty()) return false;
        if (plan.getOrderBy() != null && !plan.getOrderBy().isEmpty()) return false;
        if (plan.getSelect() != null && !plan.getSelect().isEmpty()) return false;

        StructuredAggregateSpec aggregate = plan.getAggregate();
        if (aggregate == null || aggregate.operation() == null) return false;
        if (isCountMetric(aggregate)) return true;
        return isTitleLengthExtremum(aggregate);
    }

    @Override
    public StructuredPushdownResult executePushdown(StructuredPipelinePlan plan) {
        if (!supports(plan)) return StructuredPushdownResult.unsupported("patent aggregate plan is not pushdown-safe");
        StructuredAggregateSpec aggregate = plan.getAggregate();
        if (isCountMetric(aggregate)) return executeCount(plan, aggregate);
        return executeTitleLengthExtremum(plan, aggregate);
    }

    private StructuredPushdownResult executeCount(StructuredPipelinePlan plan, StructuredAggregateSpec aggregate) {
        String metric = aggregate.metricCode().toUpperCase();
        StructuredAggregateReqDTO req = new StructuredAggregateReqDTO();
        req.setKbId(plan.getScope().getCurrentKbId());
        req.setDomainCode(PatentStructuredPack.DOMAIN_CODE);
        req.setMetricCode(metric);
        req.setPublishedOnly(true);
        req.setResolvedEntityIds(plan.getScope().getResolvedEntityIds());

        CommonResult<StructuredAggregateRespDTO> rpc = aggregateApi.aggregate(req);
        if (rpc == null || !rpc.isSuccess() || rpc.getData() == null) {
            return StructuredPushdownResult.failed("authoritative structured aggregate rpc failed");
        }
        StructuredAggregateRespDTO data = rpc.getData();
        if (!data.isCompleteDataset() || data.getValue() == null || data.getSourceRowCount() == null) {
            return StructuredPushdownResult.failed("authoritative structured aggregate is incomplete");
        }
        if (data.getSourceRowCount() > Integer.MAX_VALUE) {
            return StructuredPushdownResult.failed("structured source row count exceeds runtime integer range");
        }

        long value = data.getValue();
        Map<String, Object> metadata = baseMetadata(Operation.COUNT, data.getSourceRowCount().intValue());
        metadata.put("metricCode", metric);
        metadata.put("authoritativeEmpty", value == 0L);
        metadata.put("scalarValue", (double) value);
        metadata.put("dataGrain", (PatentStructuredPack.METRIC_DOCUMENT_COUNT.equals(metric)
                ? DataGrain.SOURCE_RECORD : DataGrain.LOGICAL_ENTITY).name());

        StructuredPipelineResult result = new StructuredPipelineResult(
                true, null, List.of(), (double) value, true, value == 0L,
                data.getSourceRowCount().intValue(), 0, metadata);
        return StructuredPushdownResult.succeeded(result);
    }

    private StructuredPushdownResult executeTitleLengthExtremum(StructuredPipelinePlan plan,
                                                                 StructuredAggregateSpec aggregate) {
        if (orderApi == null || pageApi == null) {
            return StructuredPushdownResult.unsupported("title-length extremum pushdown dependencies are unavailable");
        }
        String direction = aggregate.operation() == Operation.MIN ? "ASC" : "DESC";
        StructuredOrderReqDTO req = new StructuredOrderReqDTO();
        req.setKbId(plan.getScope().getCurrentKbId());
        req.setDomainCode(PatentStructuredPack.DOMAIN_CODE);
        req.setFieldCode(PatentStructuredPack.FIELD_TITLE);
        req.setTransformCode(StructuredValueTransform.LENGTH.name());
        req.setDirection(direction);
        req.setLimit(1);
        req.setPublishedOnly(true);
        req.setResolvedEntityIds(plan.getScope().getResolvedEntityIds());

        CommonResult<StructuredOrderRespDTO> rpc = orderApi.order(req);
        if (rpc == null || !rpc.isSuccess() || rpc.getData() == null) {
            return StructuredPushdownResult.failed("authoritative title-length extremum rpc failed");
        }
        StructuredOrderRespDTO data = rpc.getData();
        if (!data.isCompleteDataset() || data.getSourceEntityCount() == null
                || data.getMissingValueCount() == null || data.getConflictCount() == null
                || data.getDocumentIds() == null) {
            return StructuredPushdownResult.failed("authoritative title-length extremum proof is incomplete");
        }
        if (data.getSourceEntityCount() > Integer.MAX_VALUE) {
            return StructuredPushdownResult.failed("structured source entity count exceeds runtime integer range");
        }
        if (data.getMissingValueCount() > 0L) {
            return StructuredPushdownResult.failed("title-length extremum source is incomplete: missing TITLE on "
                    + data.getMissingValueCount() + " logical entity(s)");
        }
        if (data.getConflictCount() > 0L) {
            return StructuredPushdownResult.unsupported("title-length extremum found duplicate TITLE variants; JVM conflict semantics required");
        }

        int sourceCount = data.getSourceEntityCount().intValue();
        if (sourceCount == 0) {
            Map<String, Object> metadata = baseMetadata(aggregate.operation(), 0);
            metadata.put("aggregateField", PatentStructuredPack.FIELD_TITLE);
            metadata.put("aggregateTransforms", List.of(StructuredValueTransform.LENGTH.name()));
            metadata.put("authoritativeEmpty", true);
            return StructuredPushdownResult.succeeded(new StructuredPipelineResult(
                    true, null, List.of(), null, true, true, 0, 0, metadata));
        }
        if (data.getDocumentIds().size() != 1 || data.getDocumentIds().get(0) == null) {
            return StructuredPushdownResult.failed("authoritative title-length extremum returned incomplete candidate id");
        }

        String title = fetchTitle(plan, data.getDocumentIds().get(0));
        if (StrUtil.isBlank(title)) {
            return StructuredPushdownResult.failed("authoritative title-length extremum candidate materialization failed");
        }
        double value = title.codePointCount(0, title.length());
        Map<String, Object> metadata = baseMetadata(aggregate.operation(), sourceCount);
        metadata.put("pushdownOperation", "AGGREGATE_EXTREMUM");
        metadata.put("aggregateField", PatentStructuredPack.FIELD_TITLE);
        metadata.put("aggregateTransforms", List.of(StructuredValueTransform.LENGTH.name()));
        metadata.put("orderDirection", direction);
        metadata.put("authoritativeEmpty", false);
        metadata.put("scalarValue", value);
        metadata.put("winnerEntityId", data.getDocumentIds().get(0));
        metadata.put("dataGrain", DataGrain.LOGICAL_ENTITY.name());

        StructuredPipelineResult result = new StructuredPipelineResult(
                true, null, List.of(), value, true, false, sourceCount, 0, metadata);
        return StructuredPushdownResult.succeeded(result);
    }

    private String fetchTitle(StructuredPipelinePlan plan, Long documentId) {
        StructuredQueryReqDTO req = new StructuredQueryReqDTO();
        req.setKbId(plan.getScope().getCurrentKbId());
        req.setDomainCode(PatentStructuredPack.DOMAIN_CODE);
        req.setFieldCode(PatentStructuredPack.FIELD_TITLE);
        req.setPublishedOnly(true);
        req.setResolvedEntityIds(List.of(documentId));
        req.setRowCap(1);
        req.setAfterDocumentId(0L);

        CommonResult<StructuredQueryRespDTO> rpc = pageApi.page(req);
        if (rpc == null || !rpc.isSuccess() || rpc.getData() == null || rpc.getData().isTruncated()) return null;
        List<StructuredQueryRowDTO> rows = rpc.getData().getRows() == null ? List.of() : rpc.getData().getRows();
        if (rows.size() != 1 || rows.get(0) == null || !documentId.equals(rows.get(0).getDocumentId())) return null;
        return StrUtil.trim(rows.get(0).getTitle());
    }

    private boolean isCountMetric(StructuredAggregateSpec aggregate) {
        return aggregate.operation() == Operation.COUNT
                && aggregate.value() == null
                && StrUtil.isNotBlank(aggregate.metricCode())
                && COUNT_METRICS.contains(aggregate.metricCode().toUpperCase());
    }

    private boolean isTitleLengthExtremum(StructuredAggregateSpec aggregate) {
        if (aggregate.operation() != Operation.MIN && aggregate.operation() != Operation.MAX) return false;
        if (StrUtil.isNotBlank(aggregate.metricCode())) return false;
        StructuredValueExpression value = aggregate.value();
        return value != null
                && !value.explode()
                && PatentStructuredPack.FIELD_TITLE.equalsIgnoreCase(value.fieldCode())
                && value.transforms().equals(List.of(StructuredValueTransform.LENGTH));
    }

    private Map<String, Object> baseMetadata(Operation operation, int sourceEntityCount) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("pushdownExecuted", true);
        metadata.put("pushdownBackend", "KNOWLEDGE_SQL");
        metadata.put("aggregate", operation.name());
        metadata.put("completeDataset", true);
        metadata.put("sourceTruncated", false);
        metadata.put("outputComplete", true);
        metadata.put("resultShape", "SCALAR");
        metadata.put("sourceEntityCount", sourceEntityCount);
        metadata.put("sourceRowCount", sourceEntityCount);
        metadata.put("missingValueCount", 0);
        return metadata;
    }
}
