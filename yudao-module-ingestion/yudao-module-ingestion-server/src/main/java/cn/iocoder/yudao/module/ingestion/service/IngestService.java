package cn.iocoder.yudao.module.ingestion.service;

/**
 * 文档入库服务
 */
public interface IngestService {

    /**
     * 处理单个文档入库(持久化任务推进)
     *
     * @param documentId 文档编号
     * @param jobId      入库任务编号(C2/C3 幂等/断点续跑; null 兼容直接调用)
     */
    void ingestDocument(Long documentId, Long jobId);

}
