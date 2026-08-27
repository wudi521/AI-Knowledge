package cn.iocoder.yudao.module.knowledge.api;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredOrderReqDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredOrderRespDTO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.PatentStructuredOrderStatsDO;
import cn.iocoder.yudao.module.knowledge.dal.mysql.knowledge.PatentStructuredOrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 权威 typed order RPC。
 *
 * <p>服务端先校验受控 field/transform，再执行固定 Mapper SQL。这里不存在用户 SQL、动态列名或脚本执行。</p>
 */
@Slf4j
@RestController
public class KnowledgeStructuredOrderApiImpl implements KnowledgeStructuredOrderApi {

    private static final String DOMAIN_PATENT = "PATENT";
    private static final String FIELD_TITLE = "TITLE";
    private static final String TRANSFORM_LENGTH = "LENGTH";
    private static final int MAX_LIMIT = 50;

    private final PatentStructuredOrderMapper orderMapper;

    public KnowledgeStructuredOrderApiImpl(PatentStructuredOrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    @Override
    public CommonResult<StructuredOrderRespDTO> order(StructuredOrderReqDTO req) {
        validate(req);
        String direction = req.getDirection().trim().toUpperCase();
        boolean publishedOnly = !Boolean.FALSE.equals(req.getPublishedOnly());
        try {
            PatentStructuredOrderStatsDO stats = orderMapper.selectTitleLengthStats(
                    req.getKbId(), req.getResolvedEntityIds(), publishedOnly);
            if (stats == null || stats.getSourceEntityCount() == null
                    || stats.getMissingValueCount() == null || stats.getConflictCount() == null) {
                throw new IllegalStateException("authoritative structured order proof is incomplete");
            }

            List<Long> documentIds = List.of();
            // 缺值时仍完成了 scope 扫描，但不能声称全局排序结论；不返回候选结果避免上层误用。
            if (stats.getMissingValueCount() == 0L && stats.getConflictCount() == 0L
                    && stats.getSourceEntityCount() > 0L) {
                documentIds = orderMapper.selectTopByTitleLength(
                        req.getKbId(), req.getResolvedEntityIds(), publishedOnly,
                        direction, Math.min(MAX_LIMIT, req.getLimit()));
                if (documentIds == null) {
                    throw new IllegalStateException("authoritative structured order returned null ids");
                }
            }

            StructuredOrderRespDTO response = new StructuredOrderRespDTO();
            response.setDocumentIds(documentIds == null ? List.of() : List.copyOf(documentIds));
            response.setSourceEntityCount(stats.getSourceEntityCount());
            response.setMissingValueCount(stats.getMissingValueCount());
            response.setConflictCount(stats.getConflictCount());
            response.setCompleteDataset(true);
            return CommonResult.success(response);
        } catch (RuntimeException e) {
            log.error("[order][kbId({}) field({}) transform({}) authoritative order failed]",
                    req.getKbId(), req.getFieldCode(), req.getTransformCode(), e);
            throw e;
        }
    }

    private void validate(StructuredOrderReqDTO req) {
        if (req == null || req.getKbId() == null) {
            throw new IllegalArgumentException("structured order requires kbId");
        }
        String domain = StrUtil.blankToDefault(req.getDomainCode(), DOMAIN_PATENT).trim().toUpperCase();
        String field = StrUtil.blankToDefault(req.getFieldCode(), "").trim().toUpperCase();
        String transform = StrUtil.blankToDefault(req.getTransformCode(), "").trim().toUpperCase();
        String direction = StrUtil.blankToDefault(req.getDirection(), "").trim().toUpperCase();
        if (!DOMAIN_PATENT.equals(domain)) {
            throw new IllegalArgumentException("structured order domain is not registered: " + domain);
        }
        if (!FIELD_TITLE.equals(field) || !TRANSFORM_LENGTH.equals(transform)) {
            throw new IllegalArgumentException("structured order combination is not registered: "
                    + field + "+" + transform);
        }
        if (!"ASC".equals(direction) && !"DESC".equals(direction)) {
            throw new IllegalArgumentException("structured order direction must be ASC or DESC");
        }
        if (req.getLimit() == null || req.getLimit() < 1 || req.getLimit() > MAX_LIMIT) {
            throw new IllegalArgumentException("structured order limit must be 1.." + MAX_LIMIT);
        }
    }
}
