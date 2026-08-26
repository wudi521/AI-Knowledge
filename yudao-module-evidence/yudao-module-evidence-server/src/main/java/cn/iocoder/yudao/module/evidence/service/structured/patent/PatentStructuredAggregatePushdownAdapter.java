package cn.iocoder.yudao.module.evidence.service.structured.patent;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.evidence.service.structured.core.DataGrain;
import cn.iocoder.yudao.module.evidence.service.structured.core.Operation;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredAggregateSpec;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredPipelinePlan;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredPipelineResult;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredPushdownAdapter;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredPushdownResult;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeStructuredAggregateApi;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredAggregateReqDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredAggregateRespDTO;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Patent Domain 的第一批整计划下推：只接管无 filter/group/order 的权威 COUNT metric。
 * 复杂过滤/变换/分组仍明确 UNSUPPORTED，由 Core 回退既有完整行级语义。
 */
@Component
public class PatentStructuredAggregatePushdownAdapter implements StructuredPushdownAdapter {

    private static final Set<String> METRICS = Set.of(
            PatentStructuredPack.METRIC_DOCUMENT_COUNT,
            PatentStructuredPack.METRIC_PATENT_COUNT);

    private final KnowledgeStructuredAggregateApi aggregateApi;

    public PatentStructuredAggregatePushdownAdapter(KnowledgeStructuredAggregateApi aggregateApi) {
        this.aggregateApi = aggregateApi;
    }

    @Override
    public String domainCode() {
        return PatentStructuredPack.DOMAIN_CODE;
    }

    @Override
    public boolean supports(StructuredPipelinePlan plan) {
        if (plan == null || plan.getScope() == null || plan.getScope().getCurrentKbId() == null) return false;
        if (plan.getFilter() != null || plan.isDistinct()) return false;
        if (plan.getGroupBy() != null && !plan.getGroupBy().isEmpty()) return false;
        if (plan.getOrderBy() != null && !plan.getOrderBy().isEmpty()) return false;
        if (plan.getSelect() != null && !plan.getSelect().isEmpty()) return false;
        StructuredAggregateSpec aggregate = plan.getAggregate();
        return aggregate != null
                && aggregate.operation() == Operation.COUNT
                && aggregate.value() == null
                && aggregate.metricCode() != null
                && METRICS.contains(aggregate.metricCode().toUpperCase());
    }

    @Override
    public StructuredPushdownResult executePushdown(StructuredPipelinePlan plan) {
        if (!supports(plan)) return StructuredPushdownResult.unsupported("patent aggregate plan is not pushdown-safe");
        String metric = plan.getAggregate().metricCode().toUpperCase();
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
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("pushdownExecuted", true);
        metadata.put("pushdownBackend", "KNOWLEDGE_SQL");
        metadata.put("aggregate", Operation.COUNT.name());
        metadata.put("metricCode", metric);
        metadata.put("completeDataset", true);
        metadata.put("outputComplete", true);
        metadata.put("authoritativeEmpty", value == 0L);
        metadata.put("resultShape", "SCALAR");
        metadata.put("scalarValue", (double) value);
        metadata.put("dataGrain", (PatentStructuredPack.METRIC_DOCUMENT_COUNT.equals(metric)
                ? DataGrain.SOURCE_RECORD : DataGrain.LOGICAL_ENTITY).name());

        StructuredPipelineResult result = new StructuredPipelineResult(
                true, null, List.of(), (double) value, true, value == 0L,
                data.getSourceRowCount().intValue(), 0, metadata);
        return StructuredPushdownResult.succeeded(result);
    }
}
