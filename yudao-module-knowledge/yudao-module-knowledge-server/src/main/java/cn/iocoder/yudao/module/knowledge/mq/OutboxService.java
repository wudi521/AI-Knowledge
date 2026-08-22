package cn.iocoder.yudao.module.knowledge.mq;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.outbox.AiOutboxEventDO;
import cn.iocoder.yudao.module.knowledge.dal.mysql.outbox.AiOutboxEventMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 事务性 Outbox 服务(C2):
 * <ol>
 *   <li>业务事务内调用 {@link #record(String, Long, String, String)} 写事件(与业务同库同事务);</li>
 *   <li>事务提交后由 {@link #publishPending()} 发送 Kafka 并置 SENT(同步发送失败保留 PENDING);</li>
 *   <li>定时补偿扫描 PENDING 补发(消息可靠, 至少一次语义, 消费端幂等兜底)。</li>
 * </ol>
 */
@Slf4j
@Service
public class OutboxService {

    public static final String AGGREGATE_DOCUMENT = "DOCUMENT";
    public static final String EVENT_DOCUMENT_CREATED = "DOCUMENT_CREATED";

    @Resource
    private AiOutboxEventMapper outboxEventMapper;
    @Resource
    private KnowledgeIngestProducer knowledgeIngestProducer;

    /**
     * 记录事件(必须在业务事务内调用)
     */
    public void record(String aggregateType, Long aggregateId, String eventType, Map<String, Object> payload) {
        AiOutboxEventDO event = new AiOutboxEventDO();
        event.setAggregateType(aggregateType);
        event.setAggregateId(aggregateId);
        event.setEventType(eventType);
        event.setPayload(payload == null ? null : JSONUtil.toJsonStr(payload));
        event.setIdempotencyKey(aggregateType + ":" + aggregateId + ":" + eventType);
        event.setStatus("PENDING");
        event.setRetryCount(0);
        outboxEventMapper.insert(event);
    }

    /**
     * 发送待发事件(事务提交后调用; 补偿扫描亦调用)。失败保留 PENDING 供重试。
     */
    public void publishPending(int limit) {
        List<AiOutboxEventDO> pending = outboxEventMapper.selectPending(limit);
        for (AiOutboxEventDO event : pending) {
            try {
                if (EVENT_DOCUMENT_CREATED.equals(event.getEventType())) {
                    knowledgeIngestProducer.sendDocumentIngestSync(event.getAggregateId());
                } else {
                    log.warn("[publishPending][未知事件类型 {} 跳过]", event.getEventType());
                    continue;
                }
                // 发送成功置 SENT
                AiOutboxEventDO update = new AiOutboxEventDO();
                update.setId(event.getId());
                update.setStatus("SENT");
                update.setSentAt(java.time.LocalDateTime.now());
                outboxEventMapper.updateById(update);
                log.info("[publishPending][事件 {} 发送成功: {}:{}:{}]", event.getId(),
                        event.getAggregateType(), event.getAggregateId(), event.getEventType());
            } catch (Exception e) {
                log.error("[publishPending][事件 {} 发送失败, 保留 PENDING 待补偿: {}]", event.getId(), e.getMessage());
                AiOutboxEventDO update = new AiOutboxEventDO();
                update.setId(event.getId());
                update.setStatus("FAILED");
                update.setRetryCount((event.getRetryCount() == null ? 0 : event.getRetryCount()) + 1);
                outboxEventMapper.updateById(update);
            }
        }
    }

    /**
     * 定时补偿: 每分钟扫描 PENDING/FAILED 事件补发(至少一次语义, 消费端幂等去重)
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void scheduledCompensation() {
        try {
            publishPending(100);
        } catch (Exception e) {
            log.warn("[scheduledCompensation][Outbox 补偿扫描异常: {}]", e.getMessage());
        }
    }

}
