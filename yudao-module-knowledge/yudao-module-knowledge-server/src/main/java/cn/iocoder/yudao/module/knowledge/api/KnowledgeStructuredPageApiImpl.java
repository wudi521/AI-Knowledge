package cn.iocoder.yudao.module.knowledge.api;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryReqDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryRespDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryRowDTO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.AiDocumentDO;
import cn.iocoder.yudao.module.knowledge.dal.mysql.knowledge.AiDocumentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 结构化源 keyset 分页实现。
 *
 * <p>当前只开放 PATENT 白名单。一次 RPC 只读有限页；Evidence 端必须沿 nextDocumentId
 * 读到 truncated=false 才能声称完整覆盖。</p>
 */
@Slf4j
@RestController
public class KnowledgeStructuredPageApiImpl implements KnowledgeStructuredPageApi {

    private static final int DEFAULT_PAGE_SIZE = 1000;
    private static final int MAX_PAGE_SIZE = 2000;
    private static final String DOMAIN_PATENT = "PATENT";

    private final AiDocumentMapper aiDocumentMapper;

    public KnowledgeStructuredPageApiImpl(AiDocumentMapper aiDocumentMapper) {
        this.aiDocumentMapper = aiDocumentMapper;
    }

    @Override
    public CommonResult<StructuredQueryRespDTO> page(StructuredQueryReqDTO request) {
        if (request == null || request.getKbId() == null) {
            throw new IllegalArgumentException("structured page requires kbId");
        }
        String domainCode = StrUtil.blankToDefault(request.getDomainCode(), DOMAIN_PATENT).trim().toUpperCase();
        if (!DOMAIN_PATENT.equals(domainCode)) {
            throw new IllegalArgumentException("structured page domain is not registered: " + domainCode);
        }

        int pageSize = request.getRowCap() == null || request.getRowCap() <= 0
                ? DEFAULT_PAGE_SIZE : Math.min(MAX_PAGE_SIZE, request.getRowCap());
        long afterDocumentId = request.getAfterDocumentId() == null
                ? 0L : Math.max(0L, request.getAfterDocumentId());

        try {
            List<AiDocumentDO> fetched = aiDocumentMapper.selectStructuredPatentDocumentsPage(
                    request.getKbId(), request.getResolvedEntityIds(), request.getPublishedOnly(),
                    afterDocumentId, pageSize + 1);
            if (fetched == null) fetched = List.of();

            boolean hasMore = fetched.size() > pageSize;
            List<AiDocumentDO> pageDocs = hasMore
                    ? new ArrayList<>(fetched.subList(0, pageSize))
                    : new ArrayList<>(fetched);

            StructuredQueryRespDTO response = new StructuredQueryRespDTO();
            List<StructuredQueryRowDTO> rows = new ArrayList<>(pageDocs.size());
            for (AiDocumentDO document : pageDocs) rows.add(toRow(document, request.getMetricCode()));
            response.setRows(rows);
            response.setTruncated(hasMore);
            response.setNextDocumentId(hasMore && !pageDocs.isEmpty()
                    ? pageDocs.get(pageDocs.size() - 1).getId() : null);
            return CommonResult.success(response);
        } catch (RuntimeException e) {
            log.error("[page][kbId({}) after({}) structured source page failed]",
                    request.getKbId(), afterDocumentId, e);
            throw e;
        }
    }

    private StructuredQueryRowDTO toRow(AiDocumentDO document, String metricCode) {
        StructuredQueryRowDTO row = new StructuredQueryRowDTO();
        row.setDocumentId(document.getId());
        row.setDocumentName(document.getName());
        JSONObject metadata = metadata(document);
        row.setTitle(text(metadata, "title"));
        row.setApplicationNo(text(metadata, "applicationNo"));
        row.setPublicationNo(text(metadata, "publicationNo"));
        row.setApplicant(listText(metadata, "applicants", "applicant"));
        row.setInventor(listText(metadata, "inventors", "inventor"));
        row.setFilingDate(text(metadata, "filingDate", "applicationDate"));
        row.setPublicationDate(text(metadata, "publicationDate", "publishDate"));
        row.setValue(metricValue(metadata, metricCode));
        return row;
    }

    private JSONObject metadata(AiDocumentDO document) {
        if (document == null || StrUtil.isBlank(document.getDomainMetadata())) return new JSONObject();
        try {
            return JSONUtil.parseObj(document.getDomainMetadata());
        } catch (Exception e) {
            // 与旧 structuredQuery 一致：单条历史脏元数据按字段缺失处理，由上层 Completeness Guard 决定能否下结论。
            log.warn("[metadata][documentId({}) invalid domain metadata: {}]", document.getId(), e.getMessage());
            return new JSONObject();
        }
    }

    private String text(JSONObject metadata, String... keys) {
        if (metadata == null) return null;
        for (String key : keys) {
            String value = metadata.getStr(key);
            if (StrUtil.isNotBlank(value)) return value;
        }
        return null;
    }

    private String listText(JSONObject metadata, String pluralKey, String singularKey) {
        if (metadata == null) return null;
        Object raw = metadata.get(pluralKey);
        if (raw instanceof Iterable<?> values) {
            List<String> items = new ArrayList<>();
            for (Object value : values) {
                if (value != null && StrUtil.isNotBlank(String.valueOf(value))) items.add(String.valueOf(value));
            }
            if (!items.isEmpty()) return String.join("、", items);
        }
        return text(metadata, singularKey);
    }

    private Double metricValue(JSONObject metadata, String metricCode) {
        if (StrUtil.isBlank(metricCode)) return null;
        return switch (metricCode.trim().toUpperCase()) {
            case "DOCUMENT_COUNT" -> 1D;
            case "CLAIM_COUNT" -> {
                Integer value = metadata == null ? null : metadata.getInt("claimCount");
                yield value == null ? 0D : value.doubleValue();
            }
            default -> null;
        };
    }
}
