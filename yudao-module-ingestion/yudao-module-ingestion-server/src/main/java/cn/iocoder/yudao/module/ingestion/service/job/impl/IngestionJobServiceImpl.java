package cn.iocoder.yudao.module.ingestion.service.job.impl;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.ingestion.dal.dataobject.AiIngestionJobDO;
import cn.iocoder.yudao.module.ingestion.dal.mysql.AiIngestionJobMapper;
import cn.iocoder.yudao.module.ingestion.service.job.IngestionJobService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 入库任务服务实现
 */
@Slf4j
@Service
public class IngestionJobServiceImpl implements IngestionJobService {

    @Resource
    private AiIngestionJobMapper ingestionJobMapper;

    @Override
    public AiIngestionJobDO getOrCreate(Long documentId) {
        AiIngestionJobDO job = ingestionJobMapper.selectByDocument(documentId);
        if (job != null) {
            // 已成功/进行中: 重复消息不再执行
            if ("SUCCEEDED".equals(job.getStatus()) || "RUNNING".equals(job.getStatus())) {
                return null;
            }
            return job; // FAILED/PENDING: 复用重跑
        }
        AiIngestionJobDO create = new AiIngestionJobDO();
        create.setDocumentId(documentId);
        create.setJobType("INGEST");
        create.setStage("FETCH");
        create.setStatus("PENDING");
        create.setIdempotencyKey(String.valueOf(documentId));
        create.setRetryCount(0);
        create.setMaxRetry(3);
        create.setProgress(0);
        create.setOptimisticVersion(0);
        ingestionJobMapper.insert(create);
        return create;
    }

    @Override
    public boolean isSucceeded(Long documentId) {
        AiIngestionJobDO job = ingestionJobMapper.selectByDocument(documentId);
        return job != null && "SUCCEEDED".equals(job.getStatus());
    }

    @Override
    public void updateStage(Long jobId, String stage) {
        if (jobId == null) {
            return;
        }
        AiIngestionJobDO update = new AiIngestionJobDO();
        update.setId(jobId);
        update.setStage(stage);
        ingestionJobMapper.updateById(update);
    }

    @Override
    public void markFailed(Long jobId, String stage, String errorMessage) {
        if (jobId == null) {
            return;
        }
        AiIngestionJobDO update = new AiIngestionJobDO();
        update.setId(jobId);
        update.setStage(stage == null ? "FAILED" : stage);
        update.setStatus("FAILED");
        update.setErrorMessage(errorMessage == null ? null : cn.hutool.core.util.StrUtil.sub(errorMessage, 0, 500));
        update.setFinishedAt(LocalDateTime.now());
        ingestionJobMapper.updateById(update);
    }

    @Override
    public void markDone(Long jobId, int total) {
        if (jobId == null) {
            return;
        }
        AiIngestionJobDO update = new AiIngestionJobDO();
        update.setId(jobId);
        update.setStage("DONE");
        update.setStatus("SUCCEEDED");
        update.setTotal(total);
        update.setProgress(total);
        update.setFinishedAt(LocalDateTime.now());
        ingestionJobMapper.updateById(update);
    }

    @Override
    public void markRunning(Long jobId) {
        if (jobId == null) {
            return;
        }
        AiIngestionJobDO update = new AiIngestionJobDO();
        update.setId(jobId);
        update.setStatus("RUNNING");
        update.setStartedAt(LocalDateTime.now());
        ingestionJobMapper.updateById(update);
    }

}
