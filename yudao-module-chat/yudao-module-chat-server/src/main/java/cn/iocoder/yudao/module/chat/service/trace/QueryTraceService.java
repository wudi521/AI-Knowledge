package cn.iocoder.yudao.module.chat.service.trace;

import cn.iocoder.yudao.module.chat.dal.dataobject.trace.AiQueryTraceDO;
import cn.iocoder.yudao.module.chat.dal.dataobject.trace.AiQueryTraceStageDO;
import cn.iocoder.yudao.module.chat.dal.mysql.trace.AiQueryTraceMapper;
import cn.iocoder.yudao.module.chat.dal.mysql.trace.AiQueryTraceStageMapper;
import cn.iocoder.yudao.module.retrieval.api.dto.QueryStageTimingDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * P0-09 Query Trace 服务: 每个用户问题一个主 traceId(q- 前缀), 全链路阶段落库。
 * <p>
 * 阶段仅记录 stage/status/耗时/脱敏摘要, 禁止记录完整敏感 Prompt / Access Token / 密码 / Authorization Header。
 * 落库尽力而为: 失败仅告警, 不阻断问答主流程。
 */
@Slf4j
@Service
public class QueryTraceService {

    @Resource
    private AiQueryTraceMapper traceMapper;
    @Resource
    private AiQueryTraceStageMapper stageMapper;

    /** 生成统一主 traceId(q- + 12 位短随机) */
    public String newTraceId() {
        return "q-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /** 开始一条查询 Trace(创建主记录) */
    public void begin(String traceId, Long conversationId, String query, Long kbId, String domainCode) {
        if (traceId == null) return;
        try {
            AiQueryTraceDO trace = new AiQueryTraceDO();
            trace.setTraceId(traceId);
            trace.setConversationId(conversationId);
            trace.setQuery(query != null && query.length() > 500 ? query.substring(0, 500) : query);
            trace.setKbId(kbId);
            trace.setDomainCode(domainCode);
            trace.setStatus("RUNNING");
            trace.setStartedAt(LocalDateTime.now());
            traceMapper.insert(trace);
        } catch (Exception e) {
            log.warn("[begin][traceId({}) 创建失败, 忽略]", traceId);
        }
    }

    /** 落库全链路阶段(覆盖式; 同 traceId 先清后写) */
    public void recordStages(String traceId, List<QueryStageTimingDTO> stages) {
        if (traceId == null || stages == null || stages.isEmpty()) return;
        try {
            stageMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiQueryTraceStageDO>()
                    .eq(AiQueryTraceStageDO::getTraceId, traceId));
            for (QueryStageTimingDTO s : stages) {
                AiQueryTraceStageDO row = new AiQueryTraceStageDO();
                row.setTraceId(traceId);
                row.setSeq(s.getSeq());
                row.setStage(s.getStage());
                row.setStatus(s.getStatus());
                row.setElapsedMs(s.getElapsedMs());
                row.setSkipped(s.getSkipped());
                row.setErrorCode(s.getErrorCode());
                row.setErrorMessage(s.getErrorMessage());
                row.setModelCallId(s.getModelCallId());
                row.setInputSummary(s.getInputSummary());
                row.setOutputSummary(s.getOutputSummary());
                stageMapper.insert(row);
            }
        } catch (Exception e) {
            log.warn("[recordStages][traceId({}) 阶段落库失败, 忽略: {}]", traceId, e.getMessage());
        }
    }

    /** 完成一条查询 Trace(回填 route/耗时/状态) */
    public void finish(String traceId, String route, long totalMs, String status) {
        if (traceId == null) return;
        try {
            AiQueryTraceDO trace = traceMapper.selectByTraceId(traceId);
            if (trace == null) return;
            trace.setRoute(route);
            trace.setTotalMs(totalMs);
            trace.setStatus(status);
            trace.setFinishedAt(LocalDateTime.now());
            traceMapper.updateById(trace);
        } catch (Exception e) {
            log.warn("[finish][traceId({}) 更新失败, 忽略]", traceId);
        }
    }

    /** 查询一条 Trace 主记录 */
    public AiQueryTraceDO getTrace(String traceId) {
        return traceMapper.selectByTraceId(traceId);
    }

    /** 查询 Trace 的阶段列表(按 seq 升序) */
    public List<AiQueryTraceStageDO> getStages(String traceId) {
        return stageMapper.selectListByTraceId(traceId);
    }

    /** 查询一批 traceId 的主记录(traceId -> DO), 供会话级检索 */
    public Map<String, AiQueryTraceDO> getTraceMap(java.util.Collection<String> traceIds) {
        if (traceIds == null || traceIds.isEmpty()) return Map.of();
        return traceMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiQueryTraceDO>()
                        .in(AiQueryTraceDO::getTraceId, traceIds))
                .stream().collect(java.util.stream.Collectors.toMap(AiQueryTraceDO::getTraceId, t -> t, (a, b) -> a));
    }

}
