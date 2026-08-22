package cn.iocoder.yudao.module.ingestion.mq;

import cn.iocoder.yudao.module.ingestion.dal.dataobject.AiIngestionJobDO;
import cn.iocoder.yudao.module.ingestion.service.IngestService;
import cn.iocoder.yudao.module.ingestion.service.job.IngestionJobService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 文档入库消息消费者(C2/C3): 持久化任务幂等——重复消息不重复执行。
 */
@Slf4j
@Component
public class KnowledgeIngestConsumer {

    @Resource
    private IngestService ingestService;
    @Resource
    private IngestionJobService ingestionJobService;

    @KafkaListener(topics = "knowledge-ingest", groupId = "ingestion-server")
    public void onMessage(Long documentId) {
        log.info("[onMessage][收到入库任务, documentId={}]", documentId);
        // 幂等: 任务已成功则跳过(Outbox 至少一次语义, 重复消息由此处去重)
        if (ingestionJobService.isSucceeded(documentId)) {
            log.info("[onMessage][文档 {} 入库任务已成功, 跳过重复消息]", documentId);
            return;
        }
        AiIngestionJobDO job = ingestionJobService.getOrCreate(documentId);
        if (job == null) {
            // getOrCreate 返回 null = 任务 RUNNING(进行中)或 SUCCEEDED(已被上面拦截)
            log.info("[onMessage][文档 {} 入库任务进行中, 跳过重复消息]", documentId);
            return;
        }
        ingestService.ingestDocument(documentId, job.getId());
    }

}
