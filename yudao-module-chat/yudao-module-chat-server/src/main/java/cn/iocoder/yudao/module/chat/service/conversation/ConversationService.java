package cn.iocoder.yudao.module.chat.service.conversation;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.chat.controller.admin.conversation.vo.ConversationPageReqVO;
import cn.iocoder.yudao.module.chat.dal.dataobject.conversation.AiConversationDO;
import cn.iocoder.yudao.module.chat.dal.mysql.conversation.AiConversationMapper;
import cn.iocoder.yudao.module.chat.enums.conversation.ConversationStatusEnum;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.List;

import static cn.iocoder.yudao.module.chat.enums.ErrorCodeConstants.CONVERSATION_NOT_EXISTS;

/**
 * 会话 Service: 会话生命周期状态机 + 会话信息维护
 * <p>
 * 状态机(见 {@link ConversationStatusEnum}): ACTIVE→TRANSFERRED、ACTIVE→CLOSED、TRANSFERRED→CLOSED, CLOSED 为终态。
 * 所有状态变更均通过 {@link LambdaUpdateWrapper} 的 WHERE status IN (合法来源) 原子守卫完成,
 * 避免"先读后写"的 TOCTOU 竞态 —— 并发下仅来源状态仍匹配的那一次更新生效。
 */
@Slf4j
@Service
@Validated
public class ConversationService {

    @Resource
    private AiConversationMapper aiConversationMapper;

    /**
     * 创建会话: status=ACTIVE, channel 默认 WEB, customerId 默认 anonymous(null-safe)
     */
    public AiConversationDO createConversation(String channel, String customerId) {
        AiConversationDO conversation = new AiConversationDO();
        conversation.setChannel(StrUtil.blankToDefault(channel, "WEB"));
        conversation.setCustomerId(StrUtil.blankToDefault(customerId, "anonymous"));
        conversation.setStatus(ConversationStatusEnum.ACTIVE.getStatus());
        aiConversationMapper.insert(conversation);
        return conversation;
    }

    /**
     * 查询会话, 不存在返回 null
     */
    public AiConversationDO getConversation(Long id) {
        return aiConversationMapper.selectById(id);
    }

    /**
     * 状态迁移(原子, 带状态守卫)
     *
     * @return 是否迁移成功; 会话不存在 / 目标状态非法 / 当前状态不允许该迁移(含并发冲突)时返回 false
     */
    public boolean updateStatus(Long id, String status) {
        ConversationStatusEnum target = ConversationStatusEnum.getByStatus(status);
        if (target == null) {
            log.warn("[updateStatus][会话 {} 目标状态非法: {}]", id, status);
            return false;
        }
        List<String> fromStatuses = target.getAllowedFromStatuses();
        if (CollUtil.isEmpty(fromStatuses)) {
            // 无合法来源(如 CLOSED 为终态不可再转)
            return false;
        }
        // TOCTOU 守卫: WHERE status IN (合法来源), 并发下仅一次生效
        return aiConversationMapper.update(null, new LambdaUpdateWrapper<AiConversationDO>()
                .eq(AiConversationDO::getId, id)
                .in(AiConversationDO::getStatus, fromStatuses)
                .set(AiConversationDO::getStatus, status)) > 0;
    }

    /**
     * 记录转人工信息(摘要 + 原因)并迁移到 TRANSFERRED(仅 ACTIVE 可发起)
     */
    public void updateTransferInfo(Long id, String summary, String transferReason) {
        requireConversation(id);
        int rows = aiConversationMapper.update(null, new LambdaUpdateWrapper<AiConversationDO>()
                .eq(AiConversationDO::getId, id)
                .eq(AiConversationDO::getStatus, ConversationStatusEnum.ACTIVE.getStatus())
                .set(AiConversationDO::getSummary, summary)
                .set(AiConversationDO::getTransferReason, transferReason)
                .set(AiConversationDO::getStatus, ConversationStatusEnum.TRANSFERRED.getStatus()));
        if (rows == 0) {
            log.warn("[updateTransferInfo][会话 {} 非 ACTIVE(状态已变更), 转人工未生效]", id);
        }
    }

    /**
     * 人工接单: 状态迁移到 CLOSED 并记录接单客服(仅 TRANSFERRED 可接单)
     */
    public void takeOver(Long id, Long operatorId) {
        requireConversation(id);
        int rows = aiConversationMapper.update(null, new LambdaUpdateWrapper<AiConversationDO>()
                .eq(AiConversationDO::getId, id)
                .eq(AiConversationDO::getStatus, ConversationStatusEnum.TRANSFERRED.getStatus())
                .set(AiConversationDO::getOperatorId, operatorId)
                .set(AiConversationDO::getStatus, ConversationStatusEnum.CLOSED.getStatus()));
        if (rows == 0) {
            log.warn("[takeOver][会话 {} 非 TRANSFERRED(状态已变更), 接单未生效]", id);
        }
    }

    /**
     * 更新会话意图
     */
    public void updateIntent(Long id, String intent) {
        aiConversationMapper.update(null, new LambdaUpdateWrapper<AiConversationDO>()
                .eq(AiConversationDO::getId, id)
                .set(AiConversationDO::getIntent, intent));
    }

    /**
     * 会话消息计数自增(原子 SQL: message_count = message_count + 1)
     * <p>
     * 由 {@code MessageService.addMessage} 每条消息落库后调用(USER/AI/SYSTEM 全角色计数);
     * id 为空时忽略(消息落库时会话必已存在, 防御性兜底)。
     */
    public void incrementMessageCount(Long id) {
        if (id == null) {
            return;
        }
        aiConversationMapper.update(null, new LambdaUpdateWrapper<AiConversationDO>()
                .eq(AiConversationDO::getId, id)
                .setSql("message_count = message_count + 1"));
    }

    /**
     * 会话分页(租户由框架自动过滤; 排序见 {@link AiConversationMapper#selectPage})
     */
    public PageResult<AiConversationDO> getConversationPage(ConversationPageReqVO reqVO) {
        return aiConversationMapper.selectPage(reqVO);
    }

    private AiConversationDO requireConversation(Long id) {
        AiConversationDO conversation = getConversation(id);
        if (conversation == null) {
            throw new ServiceException(CONVERSATION_NOT_EXISTS);
        }
        return conversation;
    }

}
