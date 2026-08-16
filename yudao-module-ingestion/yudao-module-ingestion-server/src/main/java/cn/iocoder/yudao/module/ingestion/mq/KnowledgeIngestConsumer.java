package cn.iocoder.yudao.module.ingestion.mq;

import cn.iocoder.yudao.module.ingestion.service.IngestService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 文档入库消息消费者
 */
@Slf4j
@Component
public class KnowledgeIngestConsumer {

    @Resource
    private IngestService ingestService;

    @KafkaListener(topics = "knowledge-ingest", groupId = "ingestion-server")
    public void onMessage(Long documentId) {
        log.info("[onMessage][收到入库任务, documentId={}]", documentId);
        ingestService.ingestDocument(documentId);
    }

}
