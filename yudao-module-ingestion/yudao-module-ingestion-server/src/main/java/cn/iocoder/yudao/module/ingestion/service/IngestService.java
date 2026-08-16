package cn.iocoder.yudao.module.ingestion.service;

/**
 * 文档入库服务
 */
public interface IngestService {

    /**
     * 处理单个文档入库
     *
     * @param documentId 文档编号
     */
    void ingestDocument(Long documentId);

}
