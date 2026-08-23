package cn.iocoder.yudao.module.evidence.service.structured.patent;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainEntityResolver;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainStructuredDataAdapter;
import cn.iocoder.yudao.module.evidence.service.structured.core.QueryScope;
import cn.iocoder.yudao.module.evidence.service.structured.core.QueryScopeType;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryPlan;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryResult;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import cn.iocoder.yudao.module.knowledge.api.dto.PatentDocumentLookupReqDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryReqDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryRespDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryRowDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Patent Structured Data Adapter(Patent Domain Pack → Knowledge 数据访问)。
 * <p>
 * 将 Core 的 StructuredQueryPlan 翻译为白名单化的 KnowledgeApi.structuredQuery 调用(非任意 SQL),
 * 返回范围内完整结构化数据集。Core 不感知专利字段, 由本适配器完成映射。
 */
@Slf4j
@Component
public class PatentStructuredDataAdapter implements DomainStructuredDataAdapter, DomainEntityResolver {

    private static final Pattern APPLICATION_NO = Pattern.compile("(?<!\\d)20\\d{10}\\.\\d(?!\\d)");
    private static final Pattern PUBLICATION_NO = Pattern.compile("(?i)\\bCN\\s*\\d{8,12}\\s*[A-Z]\\b");

    private static final Set<String> EXECUTABLE_METRICS = Set.of(
            PatentStructuredPack.METRIC_DOCUMENT_COUNT,
            PatentStructuredPack.METRIC_CLAIM_COUNT);

    private final KnowledgeApi knowledgeApi;

    public PatentStructuredDataAdapter(KnowledgeApi knowledgeApi) {
        this.knowledgeApi = knowledgeApi;
    }

    @Override
    public String adapterKey() {
        return PatentStructuredPack.ADAPTER_KEY;
    }

    @Override
    public boolean supports(String metricCode) {
        return metricCode != null && EXECUTABLE_METRICS.contains(metricCode.toUpperCase());
    }

    @Override
    public String domainCode() {
        return PatentStructuredPack.DOMAIN_CODE;
    }

    @Override
    public StructuredQueryResult execute(StructuredQueryPlan plan) {
        if (plan == null || plan.getScope() == null || plan.getScope().getCurrentKbId() == null) {
            return StructuredQueryResult.unsupported("scope 未确定");
        }
        if (!supports(plan.getMetricCode())) {
            return StructuredQueryResult.unsupported("Patent 指标暂不支持执行: " + plan.getMetricCode());
        }
        StructuredQueryReqDTO req = new StructuredQueryReqDTO();
        req.setKbId(plan.getScope().getCurrentKbId());
        req.setMetricCode(plan.getMetricCode());
        req.setPublishedOnly(!"false".equalsIgnoreCase(
                plan.getFilters().getOrDefault("publishedOnly", "true")));
        req.setResolvedEntityIds(plan.getScope().getResolvedEntityIds());
        try {
            CommonResult<StructuredQueryRespDTO> resp = knowledgeApi.structuredQuery(req);
            if (resp == null || !resp.isSuccess() || resp.getData() == null) {
                return StructuredQueryResult.unsupported("知识库结构化数据访问失败");
            }
            StructuredQueryRespDTO data = resp.getData();
            List<StructuredQueryResult.Row> rows = new ArrayList<>();
            if (data.getRows() != null) {
                for (StructuredQueryRowDTO r : data.getRows()) {
                    rows.add(StructuredQueryResult.Row.builder()
                            .entityId(r.getDocumentId())
                            .entityKey(StrUtil.isNotBlank(r.getApplicationNo()) ? r.getApplicationNo() : r.getPublicationNo())
                            .entityName(StrUtil.isNotBlank(r.getDocumentName()) ? r.getDocumentName()
                                    : (StrUtil.isNotBlank(r.getPublicationNo()) ? r.getPublicationNo() : "文档" + r.getDocumentId()))
                            .value(r.getValue())
                            .build());
                }
            }
            return StructuredQueryResult.builder()
                    .metricCode(plan.getMetricCode())
                    .operation(plan.getOperation())
                    .rows(rows)
                    .rowCount(rows.size())
                    .truncated(data.isTruncated())
                    .build();
        } catch (Exception e) {
            log.warn("[execute][metric({}) 数据访问失败: {}]", plan.getMetricCode(), e.getMessage());
            return StructuredQueryResult.unsupported("知识库结构化数据访问异常");
        }
    }

    // ========== DomainEntityResolver: 从历史/文本中抽取并定位专利对象 ==========

    @Override
    public List<ResolvedEntity> extractEntities(String text) {
        List<ResolvedEntity> result = new ArrayList<>();
        if (StrUtil.isBlank(text)) return result;
        Matcher app = APPLICATION_NO.matcher(text);
        while (app.find()) {
            result.add(new ResolvedEntity(app.group(), null, null));
        }
        Matcher pub = PUBLICATION_NO.matcher(text);
        while (pub.find()) {
            result.add(new ResolvedEntity(pub.group(), null, null));
        }
        return result;
    }

    @Override
    public List<ResolvedEntity> resolveToEntities(List<ResolvedEntity> entities, Long kbId) {
        if (entities == null || entities.isEmpty() || kbId == null) return List.of();
        Set<ResolvedEntity> resolved = new LinkedHashSet<>();
        for (ResolvedEntity e : entities) {
            if (e == null || e.identifier() == null) continue;
            PatentDocumentLookupReqDTO req = new PatentDocumentLookupReqDTO();
            req.setKbIds(List.of(kbId));
            if (APPLICATION_NO.matcher(e.identifier()).matches()) {
                req.setApplicationNo(e.identifier());
            } else if (PUBLICATION_NO.matcher(e.identifier()).matches()) {
                req.setPublicationNo(e.identifier());
            } else {
                continue;
            }
            try {
                List<Long> docIds = knowledgeApi.lookupPatentDocuments(req).getCheckedData();
                if (docIds != null) {
                    for (Long docId : docIds) {
                        resolved.add(new ResolvedEntity(e.identifier(), docId, null));
                    }
                }
            } catch (Exception ex) {
                log.warn("[resolveToEntities][identifier({}) 定位失败: {}]", e.identifier(), ex.getMessage());
            }
        }
        return new ArrayList<>(resolved);
    }
}
