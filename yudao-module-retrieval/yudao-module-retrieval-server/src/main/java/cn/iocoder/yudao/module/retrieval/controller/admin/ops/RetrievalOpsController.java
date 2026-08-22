package cn.iocoder.yudao.module.retrieval.controller.admin.ops;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.retrieval.dal.dataobject.trace.QueryStageDO;
import cn.iocoder.yudao.module.retrieval.dal.dataobject.trace.RetrievalTraceDO;
import cn.iocoder.yudao.module.retrieval.dal.mysql.trace.QueryStageMapper;
import cn.iocoder.yudao.module.retrieval.dal.mysql.trace.RetrievalTraceMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 知识运营中心 - 查询链路(Knowledge Ops Query Trace)
 */
@Tag(name = "管理后台 - 知识运营(查询链路)")
@RestController
@RequestMapping("/retrieval/ops")
@Validated
public class RetrievalOpsController {

    @Resource
    private RetrievalTraceMapper retrievalTraceMapper;
    @Resource
    private QueryStageMapper queryStageMapper;

    @GetMapping("/query-trace")
    @Operation(summary = "查询 Trace(检索轨迹 + 阶段时间轴; 按 traceId)")
    @PreAuthorize("@ss.hasPermission('ai:knowledge:query')")
    public CommonResult<Map<String, Object>> queryTrace(@RequestParam("traceId") String traceId) {
        RetrievalTraceDO trace = retrievalTraceMapper.selectOne(
                new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<RetrievalTraceDO>()
                        .eq(RetrievalTraceDO::getTraceId, traceId)
                        .last("LIMIT 1"));
        if (trace == null) {
            return success(null);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("traceId", trace.getTraceId());
        result.put("query", trace.getQuery());
        result.put("route", trace.getRoute());
        result.put("intent", trace.getIntent());
        result.put("domainCode", trace.getDomainCode());
        result.put("conversationId", trace.getConversationId());
        result.put("variantCount", trace.getVariantCount());
        result.put("bm25Hits", trace.getBm25Hits());
        result.put("vectorHits", trace.getVectorHits());
        result.put("fused", trace.getFused());
        result.put("resultCount", trace.getResultCount());
        result.put("elapsedMs", trace.getElapsedMs());
        result.put("blocked", trace.getBlocked());
        result.put("stages", queryStageMapper.selectByTraceId(traceId));
        return success(result);
    }
}
