package cn.iocoder.yudao.module.chat.service.message;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.chat.dal.dataobject.message.AiMessageDO;
import cn.iocoder.yudao.module.chat.dal.mysql.message.AiMessageMapper;
import cn.iocoder.yudao.module.chat.service.conversation.ConversationService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/**
 * 会话消息 Service: 消息落库 + 消息查询
 * <p>
 * citations / entities 为 JSON 字符串(由调用方通过 hutool JSONUtil 序列化),
 * 存储口径与 ai_evidence_eval.claims 一致。
 * 消息落库时同步自增会话消息计数({@link ConversationService#incrementMessageCount}), 单点计数全部角色。
 */
@Slf4j
@Service
@Validated
public class MessageService {

    @Resource
    private AiMessageMapper aiMessageMapper;
    @Resource
    private ConversationService conversationService;

    /**
     * 落库一条消息(null-safe), 并同步自增会话消息计数(尽力而为, 失败仅告警不阻断落库)
     *
     * @param role           角色 USER / AI / SYSTEM(为空时默认 SYSTEM)
     * @param content        消息内容(为空时存空串, 避免触发 NOT NULL 约束)
     * @param citationsJson  引用证据 JSON 数组字符串(可空)
     * @param intent         意图(可空)
     * @param entitiesJson   实体 JSON 字符串(可空)
     * @param confidence     置信度 0~1(可空)
     * @param traceId        链路追踪号(可空)
     * @return 落库后的消息(含自增 id / 框架填充的 creator、createTime、tenantId)
     */
    public AiMessageDO addMessage(Long conversationId, String role, String content, String citationsJson,
                                  String intent, String entitiesJson, BigDecimal confidence, String traceId) {
        AiMessageDO message = new AiMessageDO();
        message.setConversationId(conversationId);
        message.setRole(StrUtil.blankToDefault(role, "SYSTEM"));
        message.setContent(StrUtil.nullToEmpty(content));
        message.setCitations(citationsJson);
        message.setIntent(intent);
        message.setEntities(entitiesJson);
        message.setConfidence(confidence);
        message.setTraceId(traceId);
        aiMessageMapper.insert(message);
        // 消息计数自增(USER/AI/SYSTEM 全部角色统一在此计数, 每消息一次)
        try {
            conversationService.incrementMessageCount(conversationId);
        } catch (Exception e) {
            log.warn("[addMessage][会话({}) 消息计数自增失败, 计数可能滞后]", conversationId, e);
        }
        return message;
    }

    /**
     * 查询会话的全部消息, 按创建时间升序(聊天顺序)
     */
    public List<AiMessageDO> getMessages(Long conversationId) {
        return aiMessageMapper.selectListByConversationId(conversationId);
    }

    /**
     * 查询会话最近 limit 条 USER/AI 消息(排除 SYSTEM 交接消息), 按创建时间升序(聊天顺序)
     * <p>
     * 供历史上下文注入证据评估使用: SYSTEM 交接消息不进上下文; limit 由
     * {@code yudao.chat.max-context-messages} 配置控制(轮数截断, 每轮长度截断在证据侧)。
     */
    public List<AiMessageDO> getRecentMessages(Long conversationId, int limit) {
        List<AiMessageDO> recent = aiMessageMapper.selectRecentByConversationId(conversationId, limit);
        Collections.reverse(recent);
        return recent;
    }

}
