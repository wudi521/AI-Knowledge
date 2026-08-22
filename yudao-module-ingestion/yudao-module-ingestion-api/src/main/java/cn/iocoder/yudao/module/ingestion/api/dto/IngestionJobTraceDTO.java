package cn.iocoder.yudao.module.ingestion.api.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 入库任务 Trace(Knowledge Ops Document Trace: job + 阶段时间轴)
 */
@Data
public class IngestionJobTraceDTO {

    /** 任务编号 */
    private Long jobId;

    /** 文档编号 */
    private Long documentId;

    /** 知识库编号 */
    private Long kbId;

    /** 领域代码 */
    private String domainCode;

    /** 状态: PENDING/RUNNING/SUCCEEDED/FAILED */
    private String status;

    /** 当前阶段 */
    private String stage;

    /** 错误信息 */
    private String errorMessage;

    /** 重试次数 */
    private Integer retryCount;

    /** 开始时间 */
    private LocalDateTime startedAt;

    /** 结束时间 */
    private LocalDateTime finishedAt;

    /** 阶段时间轴(按顺序) */
    private List<Task> tasks;

    @Data
    public static class Task {
        private String stageCode;
        private String handler;
        private String status;
        private String outputSummaryJson;
        private String metricsJson;
        private String errorMessage;
        private LocalDateTime startedAt;
        private LocalDateTime finishedAt;
    }
}
