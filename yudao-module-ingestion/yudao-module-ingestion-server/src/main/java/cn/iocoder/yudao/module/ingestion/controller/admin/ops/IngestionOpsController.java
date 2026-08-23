package cn.iocoder.yudao.module.ingestion.controller.admin.ops;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.ingestion.api.dto.IngestionJobTraceDTO;
import cn.iocoder.yudao.module.ingestion.dal.dataobject.AiIngestionJobDO;
import cn.iocoder.yudao.module.ingestion.dal.dataobject.AiIngestionTaskDO;
import cn.iocoder.yudao.module.ingestion.dal.mysql.AiIngestionJobMapper;
import cn.iocoder.yudao.module.ingestion.dal.mysql.AiIngestionTaskMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 知识运营中心 - 任务中心(Knowledge Ops: 入库任务列表/详情/阶段时间轴)
 */
@lombok.extern.slf4j.Slf4j
@Tag(name = "管理后台 - 知识运营(任务中心)")
@RestController
@RequestMapping("/ingestion/ops")
@Validated
public class IngestionOpsController {

    @Resource
    private AiIngestionJobMapper jobMapper;
    @Resource
    private AiIngestionTaskMapper taskMapper;
    @Resource
    private cn.iocoder.yudao.module.ingestion.service.IngestService ingestService;

    /**
     * 重试入库(任务中心: 失败任务重新执行完整解析/切分/向量化; 绕开 Kafka 直接触发)
     */
    @org.springframework.web.bind.annotation.PostMapping("/retry-ingest")
    @Operation(summary = "重试入库(按文档)")
    @PreAuthorize("@ss.hasPermission('ai:knowledge:update')")
    public CommonResult<Boolean> retryIngest(@org.springframework.web.bind.annotation.RequestParam("documentId") Long documentId) {
        cn.iocoder.yudao.module.ingestion.dal.dataobject.AiIngestionJobDO job = jobMapper.selectByDocument(documentId);
        Long jobId = job == null ? null : job.getId();
        // 异步执行(不阻塞 HTTP; 失败由任务状态机记录, 任务中心可见)
        new Thread(() -> {
            try {
                ingestService.ingestDocument(documentId, jobId);
            } catch (Exception e) {
                log.warn("[retryIngest][文档 {} 重试入库异常: {}]", documentId, e.getMessage());
            }
        }, "retry-ingest-" + documentId).start();
        return success(true);
    }

    @GetMapping("/jobs")
    @Operation(summary = "入库任务分页(按 id 倒序, 可按状态/阶段筛选)")
    @PreAuthorize("@ss.hasPermission('ai:knowledge:query')")
    public CommonResult<PageResult<AiIngestionJobDO>> jobs(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "stage", required = false) String stage,
            @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize) {
        PageParam pageParam = new PageParam();
        pageParam.setPageNo(pageNo);
        pageParam.setPageSize(pageSize);
        return success(jobMapper.selectPage(pageParam,
                new LambdaQueryWrapperX<AiIngestionJobDO>()
                        .eqIfPresent(AiIngestionJobDO::getStatus, status)
                        .eqIfPresent(AiIngestionJobDO::getStage, stage)
                        .orderByDesc(AiIngestionJobDO::getId)));
    }

    @GetMapping("/job-detail")
    @Operation(summary = "任务详情(任务 + 阶段时间轴)")
    @PreAuthorize("@ss.hasPermission('ai:knowledge:query')")
    public CommonResult<IngestionJobTraceDTO> jobDetail(@RequestParam("jobId") Long jobId) {
        AiIngestionJobDO job = jobMapper.selectById(jobId);
        if (job == null) {
            return success(null);
        }
        IngestionJobTraceDTO dto = new IngestionJobTraceDTO();
        dto.setJobId(job.getId());
        dto.setDocumentId(job.getDocumentId());
        dto.setKbId(job.getKbId());
        dto.setDomainCode(job.getDomainCode());
        dto.setStatus(job.getStatus());
        dto.setStage(job.getStage());
        dto.setErrorMessage(job.getErrorMessage());
        dto.setRetryCount(job.getRetryCount());
        dto.setStartedAt(job.getStartedAt());
        dto.setFinishedAt(job.getFinishedAt());
        List<IngestionJobTraceDTO.Task> tasks = new ArrayList<>();
        for (AiIngestionTaskDO t : taskMapper.selectByJobId(jobId)) {
            IngestionJobTraceDTO.Task task = new IngestionJobTraceDTO.Task();
            task.setStageCode(t.getStageCode());
            task.setHandler(t.getHandler());
            task.setStatus(t.getStatus());
            task.setOutputSummaryJson(t.getOutputSummaryJson());
            task.setMetricsJson(t.getMetricsJson());
            task.setErrorMessage(t.getErrorMessage());
            task.setStartedAt(t.getStartedAt());
            task.setFinishedAt(t.getFinishedAt());
            tasks.add(task);
        }
        dto.setTasks(tasks);
        return success(dto);
    }
}
