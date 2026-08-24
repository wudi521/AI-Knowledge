package cn.iocoder.yudao.module.evidence.service.structured.patent;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainEntityResolver;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainStructuredDataAdapter;
import cn.iocoder.yudao.module.evidence.service.structured.core.QueryScope;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Patent Domain Pack 的白名单结构化数据适配器。 */
@Slf4j
@Component
public class PatentStructuredDataAdapter implements DomainStructuredDataAdapter, DomainEntityResolver {

    private static final Pattern APPLICATION_NO = Pattern.compile("(?<!\\d)20\\d{10}\\.\\d(?!\\d)");
    private static final Pattern PUBLICATION_NO = Pattern.compile("(?i)\\bCN\\s*\\d{8,12}\\s*[A-Z]\\b");

    private static final Set<String> EXECUTABLE_METRICS = Set.of(
            PatentStructuredPack.METRIC_PATENT_COUNT,
            PatentStructuredPack.METRIC_DOCUMENT_COUNT,
            PatentStructuredPack.METRIC_CLAIM_COUNT);

    private static final Set<String> EXECUTABLE_FIELDS = Set.of(
            PatentStructuredPack.FIELD_PUBLICATION_NO,
            PatentStructuredPack.FIELD_APPLICATION_NO);

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
        if (metricCode == null) return false;
        String normalized = metricCode.toUpperCase();
        return EXECUTABLE_METRICS.contains(normalized) || EXECUTABLE_FIELDS.contains(normalized);
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
        String fieldCode = plan.getFieldCode();
        if (fieldCode != null) {
            if (!EXECUTABLE_FIELDS.contains(fieldCode)) {
                return StructuredQueryResult.unsupported("Patent 字段暂无可结构化数据: " + fieldCode);
            }
        } else if (!supports(plan.getMetricCode())) {
            return StructuredQueryResult.unsupported("Patent 指标暂不支持执行: " + plan.getMetricCode());
        }

        StructuredQueryReqDTO req = new StructuredQueryReqDTO();
        req.setKbId(plan.getScope().getCurrentKbId());
        // Knowledge 数据层只负责返回完整专利文档 rows；PATENT_COUNT 的业务去重在 Domain Adapter 完成。
        req.setMetricCode(PatentStructuredPack.METRIC_PATENT_COUNT.equals(plan.getMetricCode())
                ? PatentStructuredPack.METRIC_DOCUMENT_COUNT : plan.getMetricCode());
        req.setFieldCode(fieldCode);
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
                    String fieldValue = fieldValueOf(r, fieldCode);
                    String identity = patentIdentity(r);
                    rows.add(StructuredQueryResult.Row.builder()
                            .entityId(r.getDocumentId())
                            .entityKey(StrUtil.isNotBlank(fieldValue) ? fieldValue : identity)
                            .entityName(buildEntityName(r, fieldCode))
                            .value(fieldCode != null ? null : r.getValue())
                            .build());
                }
            }

            if (PatentStructuredPack.METRIC_PATENT_COUNT.equals(plan.getMetricCode())) {
                rows = dedupePatentRows(rows);
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

    /** PATENT_COUNT 按稳定业务身份去重，保留首条 document 作为该业务实体代表。 */
    private List<StructuredQueryResult.Row> dedupePatentRows(List<StructuredQueryResult.Row> rows) {
        Map<String, StructuredQueryResult.Row> unique = new LinkedHashMap<>();
        for (StructuredQueryResult.Row row : rows) {
            String key = StrUtil.isNotBlank(row.getEntityKey()) ? normalize(row.getEntityKey())
                    : "DOC:" + row.getEntityId();
            unique.putIfAbsent(key, row);
        }
        return new ArrayList<>(unique.values());
    }

    private String patentIdentity(StructuredQueryRowDTO r) {
        if (r == null) return null;
        if (StrUtil.isNotBlank(r.getApplicationNo())) return "APP:" + normalize(r.getApplicationNo());
        if (StrUtil.isNotBlank(r.getPublicationNo())) return "PUB:" + normalize(r.getPublicationNo());
        return r.getDocumentId() == null ? null : "DOC:" + r.getDocumentId();
    }

    private String normalize(String value) {
        return value == null ? null : value.replaceAll("\\s+", "").toUpperCase();
    }

    private String fieldValueOf(StructuredQueryRowDTO r, String fieldCode) {
        if (fieldCode == null || r == null) return null;
        return switch (fieldCode) {
            case PatentStructuredPack.FIELD_PUBLICATION_NO -> r.getPublicationNo();
            case PatentStructuredPack.FIELD_APPLICATION_NO -> r.getApplicationNo();
            default -> null;
        };
    }

    private String buildEntityName(StructuredQueryRowDTO r, String fieldCode) {
        String name = StrUtil.isNotBlank(r.getDocumentName()) ? r.getDocumentName()
                : (StrUtil.isNotBlank(r.getPublicationNo()) ? r.getPublicationNo() : "文档" + r.getDocumentId());
        String fieldValue = fieldValueOf(r, fieldCode);
        return StrUtil.isBlank(fieldValue) ? name : name + " · " + fieldValue;
    }

    @Override
    public List<ResolvedEntity> extractEntities(String text) {
        List<ResolvedEntity> result = new ArrayList<>();
        if (StrUtil.isBlank(text)) return result;
        Matcher app = APPLICATION_NO.matcher(text);
        while (app.find()) result.add(new ResolvedEntity(app.group(), null, null));
        Matcher pub = PUBLICATION_NO.matcher(text);
        while (pub.find()) result.add(new ResolvedEntity(pub.group(), null, null));
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
            if (APPLICATION_NO.matcher(e.identifier()).matches()) req.setApplicationNo(e.identifier());
            else if (PUBLICATION_NO.matcher(e.identifier()).matches()) req.setPublicationNo(e.identifier());
            else continue;
            try {
                List<Long> docIds = knowledgeApi.lookupPatentDocuments(req).getCheckedData();
                if (docIds != null) {
                    for (Long docId : docIds) resolved.add(new ResolvedEntity(e.identifier(), docId, null));
                }
            } catch (Exception ex) {
                log.warn("[resolveToEntities][identifier({}) 定位失败: {}]", e.identifier(), ex.getMessage());
            }
        }
        return new ArrayList<>(resolved);
    }
}
