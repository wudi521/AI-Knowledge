package cn.iocoder.yudao.module.ingestion.service.job;

import cn.iocoder.yudao.module.ingestion.dal.dataobject.AiIngestionJobDO;

/**
 * 入库任务服务: 消费端幂等去重 + 阶段状态机 + 失败重试
 */
public interface IngestionJobService {

    /**
     * 获取或创建任务(幂等): 同一文档已有任务则复用, 避免重复消息重复入库。
     *
     * @param documentId 文档编号
     * @return 任务(存在且 SUCCEEDED/RUNNING 时返回 null 表示"无需再执行")
     */
    AiIngestionJobDO getOrCreate(Long documentId);

    /** 任务是否已成功完成(重复消息判断) */
    boolean isSucceeded(Long documentId);

    /** 推进阶段 */
    void updateStage(Long jobId, String stage);

    /** 记录失败(置 FAILED + 错误信息, 供重试判断) */
    void markFailed(Long jobId, String stage, String errorMessage);

    /** 标记成功(置 SUCCEEDED + DONE + 完成时间) */
    void markDone(Long jobId, int total);

    /** 标记进行中(RUNNING + 开始时间) */
    void markRunning(Long jobId);

}
