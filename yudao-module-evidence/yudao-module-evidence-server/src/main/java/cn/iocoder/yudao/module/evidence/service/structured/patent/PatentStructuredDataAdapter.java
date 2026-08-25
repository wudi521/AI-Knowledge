package cn.iocoder.yudao.module.evidence.service.structured.patent;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainEntityResolver;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainStructuredDataAdapter;
import cn.iocoder.yudao.module.evidence.service.structured.core.FilterExpression;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryPlan;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryResult;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeDocumentRespDTO;
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

/** Patent Domain Pack 的白名单结构化数据适配器。 */
@Slf4j
@Component
public class PatentStructuredDataAdapter implements DomainStructuredDataAdapter, DomainEntityResolver {

    private static final Set<String> EXECUTABLE_METRICS = Set.of(
            PatentStructuredPack.METRIC_PATENT_COUNT,
            PatentStructuredPack.METRIC_DOCUMENT_COUNT,
            PatentStructuredPack.METRIC_CLAIM_COUNT);

    private static final Set<String> EXECUTABLE_FIELDS = Set.of(
            PatentStructuredPack.FIELD_PUBLICATION_NO,
            PatentStructuredPack.FIELD_APPLICATION_NO,
            PatentStructuredPack.FIELD_TITLE,
            PatentStructuredPack.FIELD_APPLICANT,
            PatentStructuredPack.FIELD_INVENTOR,
            PatentStructuredPack.FIELD_FILING_DATE,
            PatentStructuredPack.FIELD_PUBLICATION_DATE);

    private final KnowledgeApi knowledgeApi;

    public PatentStructuredDataAdapter(KnowledgeApi knowledgeApi) {
        this.knowledgeApi = knowledgeApi;
    }

    @Override
    public String adapterKey() { return PatentStructuredPack.ADAPTER_KEY; }

    @Override
    public boolean supports(String metricCode) {
        if (metricCode == null) return false;
        String normalized = metricCode.toUpperCase();
        return EXECUTABLE_METRICS.contains(normalized) || EXECUTABLE_FIELDS.contains(normalized);
    }

    @Override
    public String domainCode() { return PatentStructuredPack.DOMAIN_CODE; }

    @Override
    public StructuredQueryResult execute(StructuredQueryPlan plan) {
        if (plan == null || plan.getScope() == null || plan.getScope().getCurrentKbId() == null) {
            return StructuredQueryResult.unsupported("scope 未确定");
        }

        List<String> projections = normalizedProjections(plan);
        for (String projection : projections) {
            if (!EXECUTABLE_FIELDS.contains(projection)) {
                return StructuredQueryResult.unsupported("Patent 字段暂无可结构化数据: " + projection);
            }
        }
        String fieldCode = projections.isEmpty() ? plan.getFieldCode() : projections.get(0);
        if (fieldCode != null && !EXECUTABLE_FIELDS.contains(fieldCode)) {
            return StructuredQueryResult.unsupported("Patent 字段暂无可结构化数据: " + fieldCode);
        }
        if (fieldCode == null && !supports(plan.getMetricCode())) {
            return StructuredQueryResult.unsupported("Patent 指标暂不支持执行: " + plan.getMetricCode());
        }

        StructuredQueryReqDTO req = new StructuredQueryReqDTO();
        req.setKbId(plan.getScope().getCurrentKbId());
        req.setMetricCode(PatentStructuredPack.METRIC_PATENT_COUNT.equals(plan.getMetricCode())
                ? PatentStructuredPack.METRIC_DOCUMENT_COUNT : plan.getMetricCode());
        req.setFieldCode(fieldCode);
        req.setPublishedOnly(plan.getFilters() == null
                || !"false".equalsIgnoreCase(plan.getFilters().getOrDefault("publishedOnly", "true")));
        req.setResolvedEntityIds(plan.getScope().getResolvedEntityIds());

        try {
            CommonResult<StructuredQueryRespDTO> resp = knowledgeApi.structuredQuery(req);
            if (resp == null || !resp.isSuccess() || resp.getData() == null) {
                return StructuredQueryResult.unsupported("知识库结构化数据访问失败");
            }
            StructuredQueryRespDTO data = resp.getData();
            List<StructuredQueryRowDTO> sourceRows = data.getRows() == null ? List.of() : data.getRows();
            List<Long> docIds = sourceRows.stream().map(StructuredQueryRowDTO::getDocumentId)
                    .filter(java.util.Objects::nonNull).distinct().toList();
            Map<Long, KnowledgeDocumentRespDTO> documents = docIds.isEmpty()
                    ? Map.of() : safeDocumentMap(docIds);

            List<StructuredQueryResult.Row> rows = new ArrayList<>();
            boolean metricRequested = StrUtil.isNotBlank(plan.getMetricCode())
                    && EXECUTABLE_METRICS.contains(plan.getMetricCode().toUpperCase());
            for (StructuredQueryRowDTO r : sourceRows) {
                KnowledgeDocumentRespDTO doc = documents.get(r.getDocumentId());
                String fieldValue = fieldValueOf(r, doc, fieldCode);
                String identity = patentIdentity(r);
                Map<String, String> fields = allFilterableValues(r, doc);
                rows.add(StructuredQueryResult.Row.builder()
                        .entityId(r.getDocumentId())
                        .entityKey(StrUtil.isNotBlank(fieldValue) && projections.size() <= 1 ? fieldValue : identity)
                        .entityName(buildEntityName(r, doc, projections.size() <= 1 ? fieldCode : null))
                        .value(metricRequested ? r.getValue() : null)
                        .fields(fields)
                        .build());
            }

            MergeResult merge = new MergeResult(rows, Set.of());
            if (PatentStructuredPack.ENTITY_PATENT_DOCUMENT.equals(plan.getEntityType())
                    && !PatentStructuredPack.METRIC_DOCUMENT_COUNT.equals(plan.getMetricCode())) {
                merge = mergePatentRows(rows, plan.getMetricCode());
                rows = merge.rows();
            }

            // 只有当前查询真正依赖的冲突字段才阻断，避免不相关脏字段把所有查询都判死。
            if (!merge.conflictFields().isEmpty()) {
                Set<String> required = new LinkedHashSet<>(projections);
                if (StrUtil.isNotBlank(fieldCode)) required.add(fieldCode.toUpperCase());
                collectFilterFields(plan.getFilterExpression(), required);
                for (String conflict : merge.conflictFields()) {
                    if (conflict.startsWith("@METRIC:")) {
                        if (conflict.equalsIgnoreCase("@METRIC:" + StrUtil.blankToDefault(plan.getMetricCode(), ""))) {
                            return StructuredQueryResult.unsupported("同一逻辑专利的重复记录在指标 " + plan.getMetricCode() + " 上存在冲突");
                        }
                    } else if (required.contains(conflict)) {
                        return StructuredQueryResult.unsupported("同一逻辑专利的重复记录在字段 " + conflict + " 上存在冲突");
                    }
                }
            }

            if (referencesField(plan.getFilterExpression(), PatentStructuredPack.FIELD_TITLE)
                    && rows.stream().anyMatch(row -> row.getFields() == null
                    || StrUtil.isBlank(row.getFields().get(PatentStructuredPack.FIELD_TITLE)))) {
                return StructuredQueryResult.unsupported("专利标题结构化数据不完整，无法保证标题过滤结果完整");
            }

            return StructuredQueryResult.builder()
                    .metricCode(plan.getMetricCode())
                    .operation(plan.getOperation())
                    .rows(rows)
                    .rowCount(rows.size())
                    .conflict(!merge.conflictFields().isEmpty())
                    .conflictFields(merge.conflictFields())
                    .truncated(data.isTruncated())
                    .build();
        } catch (Exception e) {
            log.warn("[execute][metric({}) 数据访问失败: {}]", plan.getMetricCode(), e.getMessage());
            return StructuredQueryResult.unsupported("知识库结构化数据访问异常");
        }
    }

    private void collectFilterFields(FilterExpression expression, Set<String> out) {
        if (expression == null || expression.getType() == null) return;
        if (expression.getType() == FilterExpression.Type.CONDITION) {
            if (StrUtil.isNotBlank(expression.getFieldCode())) out.add(expression.getFieldCode().toUpperCase());
            return;
        }
        if (expression.getChildren() != null) for (FilterExpression child : expression.getChildren()) collectFilterFields(child, out);
    }

    private boolean referencesField(FilterExpression expression, String fieldCode) {
        if (expression == null || expression.getType() == null || StrUtil.isBlank(fieldCode)) return false;
        if (expression.getType() == FilterExpression.Type.CONDITION) {
            return fieldCode.equalsIgnoreCase(expression.getFieldCode());
        }
        return expression.getChildren() != null
                && expression.getChildren().stream().anyMatch(child -> referencesField(child, fieldCode));
    }

    private Map<Long, KnowledgeDocumentRespDTO> safeDocumentMap(List<Long> ids) {
        try {
            Map<Long, KnowledgeDocumentRespDTO> map = knowledgeApi.getDocumentMap(ids).getCheckedData();
            return map == null ? Map.of() : map;
        } catch (Exception e) {
            log.warn("[safeDocumentMap][专利元数据读取失败, 仅使用基础结构化字段: {}]", e.getMessage());
            return Map.of();
        }
    }

    private List<String> normalizedProjections(StructuredQueryPlan plan) {
        if (plan.getProjections() != null && !plan.getProjections().isEmpty()) {
            return plan.getProjections().stream().filter(StrUtil::isNotBlank).map(String::toUpperCase).distinct().toList();
        }
        return plan.getFieldCode() == null ? List.of() : List.of(plan.getFieldCode().toUpperCase());
    }

    private Map<String, String> allFilterableValues(StructuredQueryRowDTO row, KnowledgeDocumentRespDTO doc) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String field : EXECUTABLE_FIELDS) values.put(field, fieldValueOf(row, doc, field));
        return values;
    }

    private MergeResult mergePatentRows(List<StructuredQueryResult.Row> rows, String metricCode) {
        Map<String, StructuredQueryResult.Row> unique = new LinkedHashMap<>();
        Set<String> conflicts = new LinkedHashSet<>();
        for (StructuredQueryResult.Row incoming : rows) {
            String key = patentIdentityFromFields(incoming);
            if (StrUtil.isBlank(key)) key = StrUtil.isNotBlank(incoming.getEntityKey()) ? normalize(incoming.getEntityKey())
                    : "DOC:" + incoming.getEntityId();
            StructuredQueryResult.Row existing = unique.get(key);
            if (existing == null) {
                unique.put(key, copyRow(incoming, key));
                continue;
            }
            Map<String, String> merged = new LinkedHashMap<>(existing.getFields() == null ? Map.of() : existing.getFields());
            Map<String, String> other = incoming.getFields() == null ? Map.of() : incoming.getFields();
            for (String field : EXECUTABLE_FIELDS) {
                String a = merged.get(field), b = other.get(field);
                if (StrUtil.isBlank(a) && StrUtil.isNotBlank(b)) merged.put(field, b);
                else if (StrUtil.isNotBlank(a) && StrUtil.isNotBlank(b) && !sameValue(field, a, b)) conflicts.add(field);
            }
            existing.setFields(merged);
            if (existing.getValue() == null && incoming.getValue() != null) existing.setValue(incoming.getValue());
            else if (existing.getValue() != null && incoming.getValue() != null
                    && Double.compare(existing.getValue(), incoming.getValue()) != 0) {
                conflicts.add("@METRIC:" + StrUtil.blankToDefault(metricCode, "UNKNOWN"));
            }
        }
        return new MergeResult(new ArrayList<>(unique.values()), Set.copyOf(conflicts));
    }

    private StructuredQueryResult.Row copyRow(StructuredQueryResult.Row row, String identity) {
        return StructuredQueryResult.Row.builder()
                .entityId(row.getEntityId())
                .entityKey(identity)
                .entityName(row.getEntityName())
                .value(row.getValue())
                .fields(new LinkedHashMap<>(row.getFields() == null ? Map.of() : row.getFields()))
                .build();
    }

    private boolean sameValue(String fieldCode, String a, String b) {
        if (PatentStructuredPack.FIELD_APPLICANT.equals(fieldCode)
                || PatentStructuredPack.FIELD_INVENTOR.equals(fieldCode)) {
            return normalizeMultiValues(a).equals(normalizeMultiValues(b));
        }
        return normalizeComparable(a).equals(normalizeComparable(b));
    }

    /** 多值字段的元素顺序不属于业务事实；“张三、李四”和“李四,张三”应视为同一集合。 */
    private Set<String> normalizeMultiValues(String value) {
        Set<String> normalized = new LinkedHashSet<>();
        if (StrUtil.isBlank(value)) return normalized;
        for (String item : value.split("[、；;，,\\n\\r]+")) {
            String v = normalizeComparable(item);
            if (StrUtil.isNotBlank(v)) normalized.add(v);
        }
        return normalized;
    }

    private String normalizeComparable(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toUpperCase();
    }

    private String patentIdentityFromFields(StructuredQueryResult.Row row) {
        if (row == null || row.getFields() == null) return null;
        String app = row.getFields().get(PatentStructuredPack.FIELD_APPLICATION_NO);
        if (StrUtil.isNotBlank(app)) return "APP:" + normalize(app);
        String pub = row.getFields().get(PatentStructuredPack.FIELD_PUBLICATION_NO);
        return StrUtil.isBlank(pub) ? null : "PUB:" + normalize(pub);
    }

    private String patentIdentity(StructuredQueryRowDTO r) {
        if (r == null) return null;
        if (StrUtil.isNotBlank(r.getApplicationNo())) return "APP:" + normalize(r.getApplicationNo());
        if (StrUtil.isNotBlank(r.getPublicationNo())) return "PUB:" + normalize(r.getPublicationNo());
        return r.getDocumentId() == null ? null : "DOC:" + r.getDocumentId();
    }

    private String normalize(String value) {
        return PatentIdentifierSupport.normalize(value);
    }

    private String fieldValueOf(StructuredQueryRowDTO r, KnowledgeDocumentRespDTO doc, String fieldCode) {
        if (fieldCode == null || r == null) return null;
        return switch (fieldCode) {
            case PatentStructuredPack.FIELD_PUBLICATION_NO -> r.getPublicationNo();
            case PatentStructuredPack.FIELD_APPLICATION_NO -> r.getApplicationNo();
            case PatentStructuredPack.FIELD_TITLE -> firstNonBlank(r.getTitle(), metadataText(doc, "title"));
            case PatentStructuredPack.FIELD_APPLICANT -> firstNonBlank(r.getApplicant(), metadataText(doc, "applicants", "applicant"));
            case PatentStructuredPack.FIELD_INVENTOR -> firstNonBlank(r.getInventor(), metadataText(doc, "inventors", "inventor"));
            case PatentStructuredPack.FIELD_FILING_DATE -> firstNonBlank(r.getFilingDate(), metadataText(doc, "filingDate", "applicationDate"));
            case PatentStructuredPack.FIELD_PUBLICATION_DATE -> firstNonBlank(r.getPublicationDate(), metadataText(doc, "publicationDate", "publishDate"));
            default -> null;
        };
    }

    private String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) if (StrUtil.isNotBlank(value)) return value;
        return null;
    }

    private String metadataText(KnowledgeDocumentRespDTO doc, String... keys) {
        if (doc == null || StrUtil.isBlank(doc.getDomainMetadata())) return null;
        try {
            JSONObject meta = JSONUtil.parseObj(doc.getDomainMetadata());
            for (String key : keys) {
                Object raw = meta.get(key);
                String text = normalizeMetadataValue(raw);
                if (StrUtil.isNotBlank(text)) return text;
            }
        } catch (Exception ignore) {
            return null;
        }
        return null;
    }

    private String normalizeMetadataValue(Object raw) {
        if (raw == null) return null;
        if (raw instanceof JSONArray array) {
            List<String> values = new ArrayList<>();
            for (Object item : array) if (item != null && StrUtil.isNotBlank(String.valueOf(item))) values.add(String.valueOf(item));
            return String.join("、", values);
        }
        if (raw instanceof Iterable<?> iterable) {
            List<String> values = new ArrayList<>();
            for (Object item : iterable) if (item != null && StrUtil.isNotBlank(String.valueOf(item))) values.add(String.valueOf(item));
            return String.join("、", values);
        }
        return String.valueOf(raw);
    }

    private String buildEntityName(StructuredQueryRowDTO r, KnowledgeDocumentRespDTO doc, String fieldCode) {
        String name = firstNonBlank(r.getTitle(), metadataText(doc, "title"), r.getDocumentName());
        name = StrUtil.isNotBlank(name) ? name : (StrUtil.isNotBlank(r.getPublicationNo()) ? r.getPublicationNo() : "文档" + r.getDocumentId());
        String fieldValue = fieldValueOf(r, doc, fieldCode);
        return StrUtil.isBlank(fieldValue) || fieldValue.equals(name) ? name : name + " · " + fieldValue;
    }

    @Override
    public List<ResolvedEntity> extractEntities(String text) {
        List<ResolvedEntity> result = new ArrayList<>();
        if (StrUtil.isBlank(text)) return result;
        Matcher app = PatentIdentifierSupport.APPLICATION_NO.matcher(text);
        while (app.find()) result.add(new ResolvedEntity(app.group(), null, null));
        Matcher pub = PatentIdentifierSupport.PUBLICATION_NO.matcher(text);
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
            if (PatentIdentifierSupport.APPLICATION_NO.matcher(e.identifier()).matches()) req.setApplicationNo(e.identifier());
            else if (PatentIdentifierSupport.PUBLICATION_NO.matcher(e.identifier()).matches()) req.setPublicationNo(e.identifier());
            else continue;
            try {
                List<Long> docIds = knowledgeApi.lookupPatentDocuments(req).getCheckedData();
                if (docIds != null) for (Long docId : docIds) resolved.add(new ResolvedEntity(e.identifier(), docId, null));
            } catch (Exception ex) {
                log.warn("[resolveToEntities][identifier({}) 定位失败: {}]", e.identifier(), ex.getMessage());
            }
        }
        return new ArrayList<>(resolved);
    }

    private record MergeResult(List<StructuredQueryResult.Row> rows, Set<String> conflictFields) { }
}
