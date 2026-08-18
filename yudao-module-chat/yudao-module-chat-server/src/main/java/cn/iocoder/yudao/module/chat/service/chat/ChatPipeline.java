package cn.iocoder.yudao.module.chat.service.chat;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.chat.channel.ChannelAdapter;
import cn.iocoder.yudao.module.chat.service.conversation.ConversationService;
import cn.iocoder.yudao.module.chat.service.evidence.EvidenceRpcAdapter;
import cn.iocoder.yudao.module.chat.service.message.MessageService;
import cn.iocoder.yudao.module.evidence.api.dto.EvidenceClaimDTO;
import cn.iocoder.yudao.module.evidence.api.dto.EvidenceEvaluateRespDTO;
import cn.iocoder.yudao.module.evidence.api.dto.EvidenceItemDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static cn.iocoder.yudao.module.chat.enums.ErrorCodeConstants.CONVERSATION_NOT_EXISTS;

/**
 * 对话编排管线: 会话 → 证据判定 → 回答或转人工
 * <p>
 * 流程:
 * <ol>
 *     <li><b>渠道解析</b>: 空渠道 → WEB; 未注册渠道(企微/钉钉等) → log.warn 降级为 WEB, 不报错;</li>
 *     <li><b>会话解析</b>: conversationId 为空 → 新建 ACTIVE 会话; 非空 → 校验存在(不存在抛
 *     {@link ServiceException} CONVERSATION_NOT_EXISTS);</li>
 *     <li><b>USER 消息落库</b>: 无论后续判定结果如何, 客户消息一律先落库;</li>
 *     <li><b>证据判定</b>: 调用 {@link EvidenceRpcAdapter#evaluate}, 失败返回 null;</li>
 *     <li><b>分流</b>: 可作答 → 落库 AI 消息并返回回答; 不可作答 → 返回转人工决策(本任务不做状态变更,
 *     见 {@link #buildTransferResult} 的 Task 4 接缝说明)。</li>
 * </ol>
 * 约束: 除 CONVERSATION_NOT_EXISTS 外本管线永不抛出; 证据 RPC 失败 → 转人工 + 原因"评估服务暂不可用"。
 */
@Slf4j
@Component
public class ChatPipeline {

    /** 未注册渠道的降级目标 */
    private static final String DEFAULT_CHANNEL = "WEB";

    /** 转人工原因: 评估服务不可用 */
    private static final String REASON_EVAL_UNAVAILABLE = "评估服务暂不可用";
    /** 转人工原因: 证据冲突 */
    private static final String REASON_CONFLICT = "证据冲突";
    /** 转人工原因: 产品不匹配 */
    private static final String REASON_PRODUCT_MISMATCH = "产品不匹配";
    /** 转人工原因: 证据不足 */
    private static final String REASON_INSUFFICIENT = "证据不足";
    /** 转人工原因: 检索阻断 */
    private static final String REASON_BLOCKED = "检索阻断";
    /** 转人工原因: Claim 验证失败 */
    private static final String REASON_CLAIM_FAIL = "Claim验证失败";
    /** 转人工原因: 证据不充分(兜底) */
    private static final String REASON_FALLBACK = "证据不充分";

    @Resource
    private ConversationService conversationService;
    @Resource
    private MessageService messageService;
    @Resource
    private EvidenceRpcAdapter evidenceRpcAdapter;
    @Resource
    private List<ChannelAdapter> channelAdapters;

    /**
     * 发送一条客户消息并返回处理结果
     *
     * @param conversationId 会话编号(为空则新建会话)
     * @param message        客户消息内容
     * @param channel        渠道标识(空或未注册 → 降级 WEB)
     * @param customerId     客户标识(新建会话时使用)
     * @return 回答或转人工决策(永不抛出, 除会话不存在外)
     */
    public ChatSendResult send(Long conversationId, String message, String channel, String customerId) {
        // 1. 渠道解析: 空 → WEB; 未注册渠道 → 降级 WEB(不报错)
        String resolvedChannel = resolveChannel(channel);

        // 2. 会话解析
        if (conversationId == null) {
            conversationId = conversationService.createConversation(resolvedChannel, customerId).getId();
        } else if (conversationService.getConversation(conversationId) == null) {
            throw new ServiceException(CONVERSATION_NOT_EXISTS);
        }

        // 3. USER 消息落库(无论判定结果如何都必须持久化)
        messageService.addMessage(conversationId, "USER", message, null, null, null, null, null);

        // 4. 证据判定(登录态租户/用户, null-safe → null 由证据侧降级)
        LoginUser loginUser = SecurityFrameworkUtils.getLoginUser();
        Long tenantId = loginUser != null ? loginUser.getTenantId() : null;
        Long userId = loginUser != null ? loginUser.getId() : null;
        EvidenceEvaluateRespDTO resp = evidenceRpcAdapter.evaluate(message, tenantId, userId, null);

        // 5. 分流: 可作答 → 回答; 否则 → 转人工决策
        if (isAnswerable(resp)) {
            return buildAnswerResult(conversationId, resp);
        }
        return buildTransferResult(conversationId, message, resp);
    }

    // ========== 渠道 ==========

    private String resolveChannel(String channel) {
        if (StrUtil.isBlank(channel)) {
            return DEFAULT_CHANNEL;
        }
        for (ChannelAdapter adapter : channelAdapters) {
            if (adapter.supports(channel)) {
                return adapter.channel();
            }
        }
        // 企微/钉钉等未注册渠道: 不报错, 降级为 WEB
        log.warn("[resolveChannel][渠道({}) 未注册适配器, 降级为 WEB]", channel);
        return DEFAULT_CHANNEL;
    }

    // ========== 判定与分流 ==========

    private boolean isAnswerable(EvidenceEvaluateRespDTO resp) {
        return resp != null
                && Boolean.TRUE.equals(resp.getAnswerable())
                && StrUtil.isNotBlank(resp.getAnswer())
                && !Boolean.TRUE.equals(resp.getClaimFail());
    }

    /**
     * 可作答路径: 落库 AI 消息(含 citations / confidence / traceId)并返回回答结果
     */
    private ChatSendResult buildAnswerResult(Long conversationId, EvidenceEvaluateRespDTO resp) {
        List<Long> citations = buildCitations(resp);
        messageService.addMessage(conversationId, "AI", resp.getAnswer(),
                JSONUtil.toJsonStr(citations), null, null,
                toConfidence(resp.getConfidence()), resp.getTraceId());
        return ChatSendResult.builder()
                .conversationId(conversationId)
                .reply(resp.getAnswer())
                .answerable(true)
                .confidence(resp.getConfidence())
                .citations(citations)
                .traceId(resp.getTraceId())
                .transferRequired(false)
                .build();
    }

    /**
     * 转人工路径: 本任务仅产出决策(原因 + 摘要草稿), 不做会话状态变更、不落 SYSTEM 消息。
     * <p>
     * <b>Task 4 接缝</b>: TransferHandler 将在此路径接入 —— 依据本结果调用
     * {@code ConversationService.updateTransferInfo(conversationId, summary, transferReason)}
     * (ACTIVE→TRANSFERRED)并落库 SYSTEM 提示消息; 本方法保持纯决策输出。
     */
    private ChatSendResult buildTransferResult(Long conversationId, String message, EvidenceEvaluateRespDTO resp) {
        String transferReason = deriveTransferReason(resp);
        String summary = buildSummaryDraft(message, transferReason, resp);
        return ChatSendResult.builder()
                .conversationId(conversationId)
                .reply(null)
                .answerable(false)
                .confidence(resp != null ? resp.getConfidence() : null)
                .citations(resp != null ? buildCitations(resp) : null)
                .traceId(resp != null ? resp.getTraceId() : null)
                .transferRequired(true)
                .transferReason(transferReason)
                .summary(summary)
                .build();
    }

    // ========== 引用证据(citations) ==========

    /**
     * 引用证据 chunkId 列表: 逐条扫描 claims, 取 verdict==SUPPORTED 且 evidenceIndex 落在
     * evidence 列表范围内的断言, 映射为 evidence[evidenceIndex].chunkId, 保序去重。
     */
    private List<Long> buildCitations(EvidenceEvaluateRespDTO resp) {
        List<Long> citations = new ArrayList<>();
        if (resp == null || resp.getClaims() == null || resp.getEvidence() == null) {
            return citations;
        }
        Set<Long> seen = new HashSet<>();
        for (EvidenceClaimDTO claim : resp.getClaims()) {
            if (claim == null || !"SUPPORTED".equalsIgnoreCase(claim.getVerdict()) || claim.getEvidenceIndex() == null) {
                continue;
            }
            int index = claim.getEvidenceIndex();
            if (index < 0 || index >= resp.getEvidence().size()) {
                continue;
            }
            EvidenceItemDTO item = resp.getEvidence().get(index);
            if (item != null && item.getChunkId() != null && seen.add(item.getChunkId())) {
                citations.add(item.getChunkId());
            }
        }
        return citations;
    }

    // ========== 转人工原因 / 摘要 ==========

    /**
     * 转人工原因推导(配置无关的结构化映射):
     * 评估服务不可用 → "评估服务暂不可用"; refusalReason 关键词映射; claimFail → "Claim验证失败"; 兜底 → "证据不充分"。
     */
    private String deriveTransferReason(EvidenceEvaluateRespDTO resp) {
        if (resp == null) {
            return REASON_EVAL_UNAVAILABLE;
        }
        String refusalReason = resp.getRefusalReason();
        if (StrUtil.isNotBlank(refusalReason)) {
            if (refusalReason.contains("冲突")) {
                return REASON_CONFLICT;
            }
            if (refusalReason.contains("产品不匹配")) {
                return REASON_PRODUCT_MISMATCH;
            }
            if (refusalReason.contains("证据不足") || refusalReason.contains("未检索")) {
                return REASON_INSUFFICIENT;
            }
            if (refusalReason.contains("阻断") || refusalReason.contains("拒绝作答")) {
                return REASON_BLOCKED;
            }
        }
        if (Boolean.TRUE.equals(resp.getClaimFail())) {
            return REASON_CLAIM_FAIL;
        }
        return REASON_FALLBACK;
    }

    /**
     * 摘要草稿(Task 4 TransferHandler 落库前可再提炼):
     * "客户问题: {message} | 原因: {reason}" + (有 AI 建议时) " | AI建议: {answer 前 100 字}"
     */
    private String buildSummaryDraft(String message, String transferReason, EvidenceEvaluateRespDTO resp) {
        StringBuilder summary = new StringBuilder("客户问题: ").append(message)
                .append(" | 原因: ").append(transferReason);
        if (resp != null && StrUtil.isNotBlank(resp.getAnswer())) {
            summary.append(" | AI建议: ").append(StrUtil.maxLength(resp.getAnswer(), 100));
        }
        return summary.toString();
    }

    // ========== 工具 ==========

    private BigDecimal toConfidence(Double confidence) {
        if (confidence == null) {
            return null;
        }
        return BigDecimal.valueOf(confidence).setScale(4, RoundingMode.HALF_UP);
    }

}
