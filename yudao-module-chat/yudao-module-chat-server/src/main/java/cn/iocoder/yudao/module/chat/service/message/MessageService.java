package cn.iocoder.yudao.module.chat.service.message;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.chat.dal.dataobject.message.AiMessageDO;
import cn.iocoder.yudao.module.chat.dal.mysql.message.AiMessageMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.util.List;

/**
 * 会话消息 Service: 消息落库 + 消息查询
 * <p>
 * citations / entities 为 JSON 字符串(由调用方通过 hutool JSONUtil 序列化),
 * 存储口径与 ai_evidence_eval.claims 一致。
 */
@Slf4j
@Service
@Validated
public class MessageService {

    @Resource
    private AiMessageMapper aiMessageMapper;

    /**
     * 落库一条消息(null-safe)
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
        return message;
    }

    /**
     * 查询会话的全部消息, 按创建时间升序(聊天顺序)
     */
    public List<AiMessageDO> getMessages(Long conversationId) {
        return aiMessageMapper.selectListByConversationId(conversationId);
    }

}
