package cn.iocoder.yudao.module.knowledge.api;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredAggregateReqDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredAggregateRespDTO;
import cn.iocoder.yudao.module.knowledge.dal.mysql.knowledge.AiDocumentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 权威结构化聚合 RPC 实现。
 *
 * <p>这里只做白名单 metric -> 固定 Mapper SQL 的映射。任何数据库故障向上抛出，禁止把故障包装成 0。</p>
 */
@Slf4j
@RestController
@Validated
public class KnowledgeStructuredAggregateApiImpl implements KnowledgeStructuredAggregateApi {

    private final AiDocumentMapper documentMapper;

    public KnowledgeStructuredAggregateApiImpl(AiDocumentMapper documentMapper) {
        this.documentMapper = documentMapper;
    }

    @Override
    public CommonResult<StructuredAggregateRespDTO> aggregate(StructuredAggregateReqDTO req) {
        if (req == null || req.getKbId() == null || StrUtil.isBlank(req.getMetricCode())) {
            throw new IllegalArgumentException("structured aggregate requires kbId and metricCode");
        }
        String metric = req.getMetricCode().trim().toUpperCase();
        boolean publishedOnly = !Boolean.FALSE.equals(req.getPublishedOnly());
        Long value;
        try {
            value = switch (metric) {
                case "DOCUMENT_COUNT" -> documentMapper.countStructuredDocuments(
                        req.getKbId(), req.getResolvedEntityIds(), publishedOnly);
                case "PATENT_COUNT" -> {
                    if (StrUtil.isNotBlank(req.getDomainCode())
                            && !"PATENT".equalsIgnoreCase(req.getDomainCode())) {
                        throw new IllegalArgumentException("PATENT_COUNT requires PATENT domain");
                    }
                    yield documentMapper.countStructuredPatentEntities(
                            req.getKbId(), req.getResolvedEntityIds(), publishedOnly);
                }
                default -> throw new IllegalArgumentException("unsupported structured aggregate metric: " + metric);
            };
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("[aggregate][kbId({}) metric({}) authoritative aggregate failed]", req.getKbId(), metric, e);
            throw new IllegalStateException("authoritative structured aggregate failed", e);
        }
        if (value == null) {
            throw new IllegalStateException("authoritative structured aggregate returned null");
        }

        StructuredAggregateRespDTO response = new StructuredAggregateRespDTO();
        response.setMetricCode(metric);
        response.setValue(value);
        response.setSourceRowCount(value);
        response.setCompleteDataset(true);
        return success(response);
    }
}
