package cn.iocoder.yudao.module.ingestion.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka 消费失败死信配置(P0 可靠性加固)
 * <p>
 * 消费 knowledge-ingest 失败(解析/切分/向量化异常)时:
 * 1. 固定间隔重试 {@code MAX_ATTEMPTS} 次(默认 3, 含首次), 应对瞬时故障;
 * 2. 重试耗尽仍失败 → 消息写入死信主题 {@code knowledge-ingest-dlq} + log.error 告警,
 *    不再无限重投阻塞消费者; 运维可消费 DLQ 补偿重试。
 * <p>
 * 说明: 该 {@link DefaultErrorHandler} bean 会被 Spring Kafka 自动配置应用
 * 到所有未显式指定 errorHandler 的 {@code @KafkaListener} 容器工厂。
 */
@Slf4j
@Configuration
public class KafkaErrorHandlerConfig {

    /** 死信主题 */
    public static final String TOPIC_INGEST_DLQ = "knowledge-ingest-dlq";

    /** 最大尝试次数(含首次); 3 = 首次 + 2 次重试 */
    private static final int MAX_ATTEMPTS = 3;

    /** 重试间隔(ms): 1 秒 */
    private static final long BACKOFF_INTERVAL_MS = 1_000L;

    @Bean
    public DefaultErrorHandler kafkaIngestErrorHandler(KafkaTemplate<String, Object> kafkaTemplate) {
        // 死信恢复器: 发送到死信主题 knowledge-ingest-dlq(与原主题同分区, 保留原始 key/value 便于追溯)
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new org.apache.kafka.common.TopicPartition(TOPIC_INGEST_DLQ, record.partition()));
        // 固定间隔重试 MAX_ATTEMPTS-1 次, 重试耗尽 → 写死信(不再无限重投阻塞消费者)
        return new DefaultErrorHandler(recoverer, new FixedBackOff(BACKOFF_INTERVAL_MS, MAX_ATTEMPTS - 1));
    }

}
