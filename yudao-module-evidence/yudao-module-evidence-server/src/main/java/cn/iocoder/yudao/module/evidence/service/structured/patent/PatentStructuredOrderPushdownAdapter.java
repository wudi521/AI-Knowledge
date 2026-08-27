package cn.iocoder.yudao.module.evidence.service.structured.patent;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredOrderSpec;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredPipelinePlan;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredPipelineResult;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredPushdownAdapter;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredPushdownResult;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredValueExpression;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredValueTransform;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeStructuredOrderApi;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeStructuredPageApi;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredOrderReqDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredOrderRespDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryReqDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryRespDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryRowDTO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * PATENT 的 typed ORDER BY + LIMIT 权威下推。
 *
 * <p>这里匹配的是结构化执行计划，不读取 originalGoal，也不匹配“最长/第一”等词。
 * 当前第一批只接管 TITLE -> LENGTH -> ASC/DESC -> TopN；其它组合明确 UNSUPPORTED，
 * 由 Core 使用完整 JVM 语义回退。</p>
 */
@Component
public class PatentStructuredOrderPushdownAdapter implements StructuredPushdownAdapter {

    private static final int MAX_LIMIT = 50;

    private final KnowledgeStructuredOrderApi orderApi;
    private final KnowledgeStructuredPageApi pageApi;

    public PatentStructuredOrderPushdownAdapter(KnowledgeStructuredOrderApi orderApi,
                                                 KnowledgeStructuredPageApi pageApi) {
        this.orderApi = orderApi;
        this.pageApi = pageApi;
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
        if (plan.getFilter() != null || plan.getAggregate() != null || plan.getHaving() != null || plan.isDistinct()) return false;
        if (plan.getGroupBy() != null && !plan.getGroupBy().isEmpty()) return false;
        if (plan.getOrderBy() == null || plan.getOrderBy().size() != 1) return false;
        if (plan.getLimit() == null || plan.getLimit() < 1 || plan.getLimit() > MAX_LIMIT) return false;

        StructuredOrderSpec order = plan.getOrderBy().get(0);
        if (order == null || order.aggregateValue() || StrUtil.isNotBlank(order.metricCode())) return false;
        StructuredValueExpression value = order.value();
        if (!isTitleLength(value)) return false;

        // Pushdown 只返回能够证明无冲突的 TITLE；更多 projection 仍交给完整 fallback，避免隐藏重复记录字段冲突。
        if (plan.getSelect() != null && !plan.getSelect().isEmpty()) {
            if (plan.getSelect().size() != 1) return false;
            StructuredValueExpression projection = plan.getSelect().get(0);
            if (projection == null || projection.explode()
                    || !PatentStructuredPack.FIELD_TITLE.equalsIgnoreCase(projection.fieldCode())
                    || (projection.transforms() != null && !projection.transforms().isEmpty())) {
                return false;
            }
        }
        return true;
    }

    @Override
    public StructuredPushdownResult executePushdown(StructuredPipelinePlan plan) {
        if (!supports(plan)) return StructuredPushdownResult.unsupported("patent typed order plan is not pushdown-safe");

        StructuredOrderSpec order = plan.getOrderBy().get(0);
        StructuredOrderReqDTO req = new StructuredOrderReqDTO();
        req.setKbId(plan.getScope().getCurrentKbId());
        req.setDomainCode(PatentStructuredPack.DOMAIN_CODE);
        req.setFieldCode(PatentStructuredPack.FIELD_TITLE);
        req.setTransformCode(StructuredValueTransform.LENGTH.name());
        req.setDirection(order.direction().name());
        req.setLimit(plan.getLimit());
        req.setPublishedOnly(true);
        req.setResolvedEntityIds(plan.getScope().getResolvedEntityIds());

        CommonResult<StructuredOrderRespDTO> rpc = orderApi.order(req);
        if (rpc == null || !rpc.isSuccess() || rpc.getData() == null) {
            return StructuredPushdownResult.failed("authoritative structured order rpc failed");
        }
        StructuredOrderRespDTO data = rpc.getData();
        if (!data.isCompleteDataset() || data.getSourceEntityCount() == null
                || data.getMissingValueCount() == null || data.getConflictCount() == null
                || data.getDocumentIds() == null) {
            return StructuredPushdownResult.failed("authoritative structured order proof is incomplete");
        }
        if (data.getSourceEntityCount() > Integer.MAX_VALUE) {
            return StructuredPushdownResult.failed("structured source entity count exceeds runtime integer range");
        }
        if (data.getMissingValueCount() > 0L) {
            return StructuredPushdownResult.failed("structured order source is incomplete: missing TITLE on "
                    + data.getMissingValueCount() + " logical entity(s)");
        }
        if (data.getConflictCount() > 0L) {
            // SQL 使用更保守的冲突判定；交回 JVM 的统一字段归一化逻辑做最终判断，而不是直接给出可能不同的结论。
            return StructuredPushdownResult.unsupported("structured order found duplicate TITLE variants; JVM conflict semantics required");
        }

        long sourceCount = data.getSourceEntityCount();
        int expected = (int) Math.min(sourceCount, (long) plan.getLimit());
        if (data.getDocumentIds().size() != expected) {
            return StructuredPushdownResult.failed("authoritative structured order returned incomplete Top-N ids");
        }

        List<StructuredPipelineResult.Row> rows = fetchRows(plan, data.getDocumentIds());
        if (rows == null) {
            return StructuredPushdownResult.failed("authoritative structured order row materialization failed");
        }

        boolean limited = sourceCount > rows.size();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("pushdownExecuted", true);
        metadata.put("pushdownBackend", "KNOWLEDGE_SQL");
        metadata.put("pushdownOperation", "ORDER_TOP_N");
        metadata.put("orderField", PatentStructuredPack.FIELD_TITLE);
        metadata.put("orderTransforms", List.of(StructuredValueTransform.LENGTH.name()));
        metadata.put("orderDirection", order.direction().name());
        metadata.put("completeDataset", true);
        metadata.put("sourceTruncated", false);
        metadata.put("sourceRowCount", (int) sourceCount);
        metadata.put("sourceEntityCount", (int) sourceCount);
        metadata.put("missingValueCount", 0);
        metadata.put("conflictCount", 0);
        metadata.put("outputCount", rows.size());
        metadata.put("fullOutputCount", (int) sourceCount);
        metadata.put("limited", limited);
        metadata.put("outputLimited", limited);
        metadata.put("outputComplete", !limited);
        metadata.put("authoritativeEmpty", sourceCount == 0L);

        return StructuredPushdownResult.succeeded(new StructuredPipelineResult(
                true, null, rows, null, true, sourceCount == 0L,
                (int) sourceCount, 0, metadata));
    }

    private List<StructuredPipelineResult.Row> fetchRows(StructuredPipelinePlan plan, List<Long> orderedIds) {
        if (orderedIds == null || orderedIds.isEmpty()) return List.of();

        StructuredQueryReqDTO req = new StructuredQueryReqDTO();
        req.setKbId(plan.getScope().getCurrentKbId());
        req.setDomainCode(PatentStructuredPack.DOMAIN_CODE);
        req.setFieldCode(PatentStructuredPack.FIELD_TITLE);
        req.setPublishedOnly(true);
        req.setResolvedEntityIds(orderedIds);
        req.setRowCap(Math.min(MAX_LIMIT, orderedIds.size()));
        req.setAfterDocumentId(0L);

        CommonResult<StructuredQueryRespDTO> rpc = pageApi.page(req);
        if (rpc == null || !rpc.isSuccess() || rpc.getData() == null || rpc.getData().isTruncated()) return null;
        List<StructuredQueryRowDTO> sourceRows = rpc.getData().getRows() == null ? List.of() : rpc.getData().getRows();
        Map<Long, StructuredQueryRowDTO> byId = sourceRows.stream()
                .filter(row -> row != null && row.getDocumentId() != null)
                .collect(Collectors.toMap(StructuredQueryRowDTO::getDocumentId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        if (!byId.keySet().containsAll(Set.copyOf(orderedIds))) return null;

        List<StructuredPipelineResult.Row> rows = new ArrayList<>(orderedIds.size());
        for (Long id : orderedIds) {
            StructuredQueryRowDTO source = byId.get(id);
            if (source == null || StrUtil.isBlank(source.getTitle())) return null;
            String title = source.getTitle().trim();
            rows.add(new StructuredPipelineResult.Row(
                    id, title, Map.of(PatentStructuredPack.FIELD_TITLE, title), null, null));
        }
        return List.copyOf(rows);
    }

    private boolean isTitleLength(StructuredValueExpression value) {
        return value != null
                && !value.explode()
                && PatentStructuredPack.FIELD_TITLE.equalsIgnoreCase(value.fieldCode())
                && value.transforms() != null
                && value.transforms().equals(List.of(StructuredValueTransform.LENGTH));
    }
}
