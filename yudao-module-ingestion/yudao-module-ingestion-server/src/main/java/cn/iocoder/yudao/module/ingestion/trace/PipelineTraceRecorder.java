package cn.iocoder.yudao.module.ingestion.trace;

import cn.iocoder.yudao.module.ingestion.dal.dataobject.AiIngestionTaskDO;
import cn.iocoder.yudao.module.ingestion.dal.mysql.AiIngestionTaskMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.function.Supplier;

/**
 * 入库管线 Trace 记录器(Knowledge Ops): 统一记录每个阶段的 状态/耗时/输入输出摘要/错误。
 * 不在各 Service 手写 insert/update; 阶段执行由 recordStage 包装, 失败不阻断主流程。
 */
@Slf4j
@Component
public class PipelineTraceRecorder {

    @Resource
    private AiIngestionTaskMapper taskMapper;

    private static final int STAGE_ORDER_BASE = 100;

    /**
     * 记录并执行一个入库阶段
     *
     * @param context     链路上下文(需含 jobId)
     * @param stageCode   阶段码(PARSE/CHUNK/EMBED/...)
     * @param handler     处理器名(如 PatentMetadataExtractor)
     * @param inputSummary 输入摘要(简短)
     * @param action      阶段执行体
     * @return 阶段结果(记录失败不影响执行)
     */
    public <T> T recordStage(TraceContext context, String stageCode, String handler,
                             String inputSummary, Supplier<T> action) {
        long start = System.currentTimeMillis();
        AiIngestionTaskDO task = beginTask(context, stageCode, handler, inputSummary);
        try {
            T result = action.get();
            completeTask(task, start, result, null);
            return result;
        } catch (Exception e) {
            failTask(task, start, e);
            throw e; // 阶段失败仍向上抛(入库主流程既有失败语义)
        }
    }

    /** 阶段入口: 创建 RUNNING 记录 */
    private AiIngestionTaskDO beginTask(TraceContext context, String stageCode, String handler, String inputSummary) {
        AiIngestionTaskDO task = new AiIngestionTaskDO();
        task.setJobId(context != null ? context.getJobId() : null);
        task.setStageCode(stageCode);
        task.setStageOrder(stageOrder(stageCode));
        task.setHandler(handler);
        task.setHandlerVersion("mvp-1.0");
        task.setAttempt(1);
        task.setStatus("RUNNING");
        task.setInputSummaryJson(trim(inputSummary, 2000));
        task.setStartedAt(LocalDateTime.now());
        try {
            taskMapper.insert(task);
        } catch (Exception e) {
            log.warn("[beginTask][阶段 {} 记录创建失败(不影响执行): {}]", stageCode, e.getMessage());
        }
        return task;
    }

    /** 阶段完成: SUCCEEDED + 耗时指标 */
    private void completeTask(AiIngestionTaskDO task, long start, Object result, Object metrics) {
        if (task == null || task.getId() == null) {
            return;
        }
        AiIngestionTaskDO update = new AiIngestionTaskDO();
        update.setId(task.getId());
        update.setStatus("SUCCEEDED");
        update.setFinishedAt(LocalDateTime.now());
        update.setMetricsJson("{\"elapsedMs\":" + (System.currentTimeMillis() - start) + "}");
        if (result != null && !(result instanceof Boolean)) {
            update.setOutputSummaryJson(trim(String.valueOf(result), 2000));
        }
        try {
            taskMapper.updateById(update);
        } catch (Exception e) {
            log.warn("[completeTask][阶段 {} 完成记录失败: {}]", task.getStageCode(), e.getMessage());
        }
    }

    /** 阶段失败: FAILED + 错误 */
    private void failTask(AiIngestionTaskDO task, long start, Exception e) {
        if (task == null || task.getId() == null) {
            return;
        }
        AiIngestionTaskDO update = new AiIngestionTaskDO();
        update.setId(task.getId());
        update.setStatus("FAILED");
        update.setFinishedAt(LocalDateTime.now());
        update.setMetricsJson("{\"elapsedMs\":" + (System.currentTimeMillis() - start) + "}");
        update.setErrorMessage(cn.hutool.core.util.StrUtil.sub(e.getMessage(), 0, 500));
        try {
            taskMapper.updateById(update);
        } catch (Exception ex) {
            log.warn("[failTask][阶段 {} 失败记录异常: {}]", task.getStageCode(), ex.getMessage());
        }
    }

    /** 阶段顺序映射 */
    private int stageOrder(String stageCode) {
        return switch (stageCode) {
            case "FETCH" -> 1;
            case "VALIDATE" -> 2;
            case "PARSE" -> 3;
            case "STRUCTURE" -> 4;
            case "METADATA" -> 5;
            case "CHUNK" -> 6;
            case "EMBED" -> 7;
            case "PERSIST" -> 8;
            case "REVIEW_PREPARE" -> 9;
            default -> STAGE_ORDER_BASE;
        };
    }

    private String trim(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
