package cn.iocoder.yudao.module.chat.controller.admin.ops;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.chat.dal.dataobject.trace.AiQueryTraceDO;
import cn.iocoder.yudao.module.chat.dal.dataobject.trace.AiQueryTraceStageDO;
import cn.iocoder.yudao.module.chat.service.trace.QueryTraceService;
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
 * 对话工作台 - 查询链路(Query Trace)
 * <p>
 * P0-09: 每个用户问题一个主 traceId(q-), Workbench "查看本次执行链路" 直接调本接口, 无需用户复制 traceId。
 */
@Tag(name = "管理后台 - 对话工作台(查询链路)")
@RestController
@RequestMapping("/chat/ops")
@Validated
public class ChatOpsController {

    @Resource
    private QueryTraceService queryTraceService;

    @GetMapping("/query-trace")
    @Operation(summary = "查询 Query Trace(主记录 + 全链路阶段时间轴; 按 traceId)")
    @PreAuthorize("@ss.hasPermission('ai:knowledge:query')")
    public CommonResult<Map<String, Object>> queryTrace(@RequestParam("traceId") String traceId) {
        AiQueryTraceDO trace = queryTraceService.getTrace(traceId);
        if (trace == null) {
            return success(null);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("traceId", trace.getTraceId());
        result.put("query", trace.getQuery());
        result.put("route", trace.getRoute());
        result.put("kbId", trace.getKbId());
        result.put("domainCode", trace.getDomainCode());
        result.put("conversationId", trace.getConversationId());
        result.put("messageId", trace.getMessageId());
        result.put("totalMs", trace.getTotalMs());
        result.put("status", trace.getStatus());
        result.put("startedAt", trace.getStartedAt());
        result.put("finishedAt", trace.getFinishedAt());
        List<AiQueryTraceStageDO> stages = queryTraceService.getStages(traceId);
        result.put("stages", stages.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", s.getSeq());
            m.put("stage", s.getStage());
            m.put("status", s.getStatus());
            m.put("elapsedMs", s.getElapsedMs());
            m.put("skipped", s.getSkipped());
            m.put("errorCode", s.getErrorCode());
            m.put("errorMessage", s.getErrorMessage());
            m.put("modelCallId", s.getModelCallId());
            m.put("inputSummary", s.getInputSummary());
            m.put("outputSummary", s.getOutputSummary());
            return m;
        }).toList());
        return success(result);
    }

}
