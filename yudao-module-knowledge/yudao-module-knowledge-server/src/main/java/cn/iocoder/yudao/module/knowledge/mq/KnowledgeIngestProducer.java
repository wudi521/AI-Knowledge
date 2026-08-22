package cn.iocoder.yudao.module.knowledge.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import jakarta.annotation.Resource;

/**
 * 知识库入库任务 {@link KafkaTemplate} 生产者
 *
 * 文档上传登记成功后，发送入库任务消息到 knowledge-ingest 主题，
 * 由下游 ingestion-server 异步消费，完成解析 / 切分 / 向量化 / 三写。
 */
@Slf4j
@Component
public class KnowledgeIngestProducer {

    public static final String TOPIC_KNOWLEDGE_INGEST = "knowledge-ingest";

    @Resource
    private KafkaTemplate<String, Object> kafkaTemplate;

    /** 同步发送(Outbox Publisher 用: 发送失败抛异常, 由 Outbox 保留事件待补偿) */
    public void sendDocumentIngestSync(Long documentId) {
        try {
            kafkaTemplate.send(TOPIC_KNOWLEDGE_INGEST, String.valueOf(documentId), documentId).get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Kafka 发送失败: " + documentId, e);
        }
    }

    /** 发送入库任务消息, payload 为文档编号 */
    public void sendDocumentIngest(Long documentId) {
        kafkaTemplate.send(TOPIC_KNOWLEDGE_INGEST, String.valueOf(documentId), documentId)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("[sendDocumentIngest][文档({}) 发送 Kafka 消息失败]", documentId, ex);
                    } else {
                        log.info("[sendDocumentIngest][文档({}) 发送 Kafka 消息成功, offset({})]",
                                documentId, result.getRecordMetadata().offset());
                    }
                });
    }

}
