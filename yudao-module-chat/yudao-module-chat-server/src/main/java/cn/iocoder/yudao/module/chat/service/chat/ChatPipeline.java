package cn.iocoder.yudao.module.chat.service.chat;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.chat.channel.ChannelAdapter;
import cn.iocoder.yudao.module.chat.dal.dataobject.conversation.AiConversationDO;
import cn.iocoder.yudao.module.chat.dal.dataobject.message.AiMessageDO;
import cn.iocoder.yudao.module.chat.enums.conversation.ConversationStatusEnum;
import cn.iocoder.yudao.module.chat.framework.chat.ChatProperties;
import cn.iocoder.yudao.module.chat.service.conversation.ConversationService;
import cn.iocoder.yudao.module.chat.service.evidence.EvidenceRpcAdapter;
import cn.iocoder.yudao.module.chat.service.message.MessageService;
import cn.iocoder.yudao.module.chat.service.transfer.TransferHandler;
import cn.iocoder.yudao.module.evidence.api.dto.ChatTurnDTO;
import cn.iocoder.yudao.module.evidence.api.dto.EvidenceClaimDTO;
import cn.iocoder.yudao.module.evidence.api.dto.EvidenceEvaluateRespDTO;
import cn.iocoder.yudao.module.evidence.api.dto.EvidenceItemDTO;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import cn.iocoder.yudao.module.chat.enums.chat.ChatRouteEnum;

import static cn.iocoder.yudao.module.chat.enums.ErrorCodeConstants.CONVERSATION_NOT_EXISTS;

/** 对话编排管线: 会话 → 证据判定 → 回答或转人工。 */
@Slf4j
@Component
public class ChatPipeline {

    private static final String DEFAULT_CHANNEL = "WEB";
    private static final String REASON_EVAL_UNAVAILABLE = "评估服务暂不可用";
    private static final String REASON_CONFLICT = "证据冲突";
    private static final String REASON_PRODUCT_MISMATCH = "产品不匹配";
    private static final String REASON_INSUFFICIENT = "证据不足";
    private static final String REASON_BLOCKED = "检索阻断";
    private static final String REASON_OUT_OF_SCOPE = "超出知识库范围";
    private static final String REASON_CLAIM_FAIL = "Claim验证失败";
    private static final String REASON_FALLBACK = "证据不充分";

    @Resource
    private ConversationService conversationService;
    @Resource
    private MessageService messageService;
    @Resource
    private EvidenceRpcAdapter evidenceRpcAdapter;
    @Resource
    private TransferHandler transferHandler;
    @Resource
    private ChatProperties chatProperties;
    @Resource
    private List<ChannelAdapter> channelAdapters;
    @Resource
    private KnowledgeApi knowledgeApi;

    public ChatSendResult send(Long conversationId, String message, String channel, String customerId) {
        return send(conversationId, message, channel, customerId, (Long) null);
    }

    public ChatSendResult send(Long conversationId, String message, String channel, String customerId,
                               Long kbId) {
        long startNanos = System.nanoTime();
        ChatSendResult result = doSend(conversationId, message, channel, customerId, kbId);
        if (result != null) {
            result.setLatencyMs((int) ((System.nanoTime() - startNanos) / 1_000_000));
        }
        return result;
    }

    /**
     * 发送消息。知识库上下文由新会话请求或既有会话的持久化绑定决定。
     */
    private ChatSendResult doSend(Long conversationId, String message, String channel, String customerId,
                                  Long kbId) {
        String resolvedChannel = resolveChannel(channel);
        LoginUser loginUser = SecurityFrameworkUtils.getLoginUser();
        Long tenantId = loginUser != null ? loginUser.getTenantId() : null;
        Long userId = loginUser != null ? loginUser.getId() : null;

        // 1. 先解析/创建会话，确保后续所有会话级操作都有真实 conversationId。
        AiConversationDO conversation;
        KnowledgeContext knowledgeContext;
        List<Long> effectiveKbIds;
        if (conversationId == null) {
            knowledgeContext = resolveKnowledgeContext(kbId, userId);
            conversation = conversationService.createConversation(resolvedChannel, customerId,
                    knowledgeContext.kbId(), knowledgeContext.domainCode(), userId);
            conversationId = conversation.getId();
            effectiveKbIds = List.of(knowledgeContext.kbId());
        } else {
            conversation = conversationService.getConversationForUser(conversationId, userId);
            if (conversation == null) {
                throw new ServiceException(CONVERSATION_NOT_EXISTS);
            }
            if (ConversationStatusEnum.CLOSED.getStatus().equals(conversation.getStatus())) {
                log.warn("[send][会话({}) 已关闭, 忽略新消息: {}]", conversationId, message);
                return buildClosedResult(conversationId);
            }
            Long persistedKbId = conversation.getKbId();
            if (persistedKbId == null) {
                persistedKbId = resolveLegacyKbId(conversation.getKbIds());
                if (persistedKbId == null) {
                    log.info("[send][会话({}) 未绑定知识库, 拒绝默认全库检索: {}]", conversationId, message);
                    return buildKbRequiredResult(conversationId);
                }
                knowledgeContext = resolveKnowledgeContext(persistedKbId, userId);
            } else {
                knowledgeContext = new KnowledgeContext(persistedKbId, conversation.getDomainCode());
            }
            effectiveKbIds = List.of(knowledgeContext.kbId());
        }

        // 3. USER 落库前读取历史，天然排除当前轮。
        List<ChatTurnDTO> history = buildHistory(messageService.getRecentMessages(
                conversationId, chatProperties.getMaxContextMessages()));

        // 4. USER 消息落库。
        messageService.addMessage(conversationId, "USER", message, null, null, null, null, null);

        // 5. 转人工早检。
        String manualReason = transferHandler.detectTransferReason(message);
        if (manualReason != null) {
            return transferHandler.handleTransfer(conversationId, message,
                    buildManualTransferResult(conversation, knowledgeContext, message, manualReason));
        }

        // 6. 证据判定。
        EvidenceEvaluateRespDTO resp = evidenceRpcAdapter.evaluate(message, tenantId, userId, null, history, effectiveKbIds);

        if (isClarifyRequired(resp)) {
            return buildClarifyResult(conversation, knowledgeContext, resp);
        }
        if (isAnswerable(resp)) {
            return buildAnswerResult(conversation, knowledgeContext, resp);
        }
        return transferHandler.handleTransfer(conversationId, message,
                buildTransferResult(conversation, knowledgeContext, message, resp));
    }

    private KnowledgeContext resolveKnowledgeContext(Long kbId, Long userId) {
        if (kbId == null || kbId <= 0) {
            throw new ServiceException(cn.iocoder.yudao.module.chat.enums.ErrorCodeConstants.KNOWLEDGE_BASE_NOT_EXISTS);
        }
        CommonResult<Set<Long>> visibleResult = knowledgeApi.getVisibleKbIds(userId);
        if (visibleResult == null || !visibleResult.isSuccess() || visibleResult.getData() == null
                || !visibleResult.getData().contains(kbId)) {
            throw new ServiceException(cn.iocoder.yudao.module.chat.enums.ErrorCodeConstants.KNOWLEDGE_BASE_NOT_EXISTS);
        }
        String domainCode = "GENERAL";
        CommonResult<Map<Long, String>> domainResult = knowledgeApi.getKbDomainCodes(List.of(kbId));
        if (domainResult != null && domainResult.isSuccess() && domainResult.getData() != null) {
            domainCode = StrUtil.blankToDefault(domainResult.getData().get(kbId), "GENERAL");
        }
        return new KnowledgeContext(kbId, domainCode);
    }

    private Long resolveLegacyKbId(String kbIds) {
        if (StrUtil.isBlank(kbIds)) {
            return null;
        }
        for (String value : kbIds.split(",")) {
            try {
                long parsed = Long.parseLong(value.trim());
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // 跳过迁移期旧数据中的非法绑定值。
            }
        }
        return null;
    }

    private record KnowledgeContext(Long kbId, String domainCode) {
    }

    private String resolveChannel(String channel) {
        if (StrUtil.isBlank(channel)) return DEFAULT_CHANNEL;
        for (ChannelAdapter adapter : channelAdapters) {
            if (adapter.supports(channel)) return adapter.channel();
        }
        log.warn("[resolveChannel][渠道({}) 未注册适配器, 降级为 WEB]", channel);
        return DEFAULT_CHANNEL;
    }

    private boolean isClarifyRequired(EvidenceEvaluateRespDTO resp) {
        return resp != null
                && resp.getMissingSlots() != null
                && !resp.getMissingSlots().isEmpty()
                && StrUtil.isNotBlank(resp.getClarifyQuestion());
    }

    private ChatSendResult buildClarifyResult(AiConversationDO conversation, KnowledgeContext knowledgeContext,
                                              EvidenceEvaluateRespDTO resp) {
        AiMessageDO aiMessage = messageService.addMessage(conversation.getId(), "AI", resp.getClarifyQuestion(),
                null, null, null, null, resp.getTraceId());
        return ChatSendResult.builder()
                .conversationId(conversation.getId())
                .messageId(aiMessage != null ? aiMessage.getId() : null)
                .kbId(knowledgeContext.kbId())
                .domainCode(knowledgeContext.domainCode())
                .intent(resolveIntent(resp))
                .route(ChatRouteEnum.ABSTAIN)
                .degraded(false)
                .answer(resp.getClarifyQuestion())
                .answerable(false)
                .confidence(resp.getConfidence())
                .citations(List.of())
                .traceId(resp.getTraceId())
                .transferRequired(false)
                .build();
    }

    private boolean isAnswerable(EvidenceEvaluateRespDTO resp) {
        return resp != null
                && Boolean.TRUE.equals(resp.getAnswerable())
                && StrUtil.isNotBlank(resp.getAnswer())
                && !Boolean.TRUE.equals(resp.getClaimFail());
    }

    private ChatSendResult buildAnswerResult(AiConversationDO conversation, KnowledgeContext knowledgeContext,
                                             EvidenceEvaluateRespDTO resp) {
        List<Long> citations = buildCitations(resp);
        List<ChatSendResult.EvidenceSummary> evidence = buildEvidenceSummaries(resp);
        AiMessageDO aiMessage = messageService.addMessage(conversation.getId(), "AI", resp.getAnswer(),
                JSONUtil.toJsonStr(citations), null, null,
                toConfidence(resp.getConfidence()), resp.getTraceId());
        return ChatSendResult.builder()
                .conversationId(conversation.getId())
                .messageId(aiMessage != null ? aiMessage.getId() : null)
                .kbId(knowledgeContext.kbId())
                .domainCode(knowledgeContext.domainCode())
                .route(resolveRoute(resp, evidence))
                .intent(resolveIntent(resp))
                .degraded(false)
                .answer(resp.getAnswer())
                .answerable(true)
                .confidence(resp.getConfidence())
                .citations(citations)
                .evidence(evidence)
                .traceId(resp.getTraceId())
                .transferRequired(false)
                .build();
    }

    private List<ChatSendResult.EvidenceSummary> buildEvidenceSummaries(EvidenceEvaluateRespDTO resp) {
        if (resp == null || resp.getEvidence() == null || resp.getEvidence().isEmpty()) return List.of();
        List<ChatSendResult.EvidenceSummary> list = new ArrayList<>();
        for (EvidenceItemDTO e : resp.getEvidence()) {
            if (e == null || e.getChunkId() == null) continue;
            String meta = e.getChunkMetadata();
            if (meta == null || meta.isBlank() || !meta.contains("sectionType")) continue;
            list.add(ChatSendResult.EvidenceSummary.builder()
                    .chunkId(e.getChunkId())
                    .documentName(e.getDocumentName())
                    .versionNo(e.getVersionNo())
                    .chunkMetadata(meta)
                    .content(e.getContent() == null ? null : StrUtil.sub(e.getContent(), 0, 500))
                    .build());
        }
        return list;
    }

    /**
     * 路由推导(P0-04): 不可作答 → ABSTAIN; 可作答时按证据文档聚焦度区分
     * SCOPED_RAG(单文档) / HYBRID_RAG(跨文档)。EXACT_METADATA / EXACT_CLAIM
     * 的确定性判定由 P0-05 / P0-06 查找能力补齐。
     */
    private String resolveRoute(EvidenceEvaluateRespDTO resp, List<ChatSendResult.EvidenceSummary> evidence) {
        if (resp == null || !Boolean.TRUE.equals(resp.getAnswerable())) {
            return ChatRouteEnum.ABSTAIN;
        }
        Set<String> docIds = new HashSet<>();
        for (ChatSendResult.EvidenceSummary e : evidence) {
            String identity = evidenceDocIdentity(e);
            if (identity != null) {
                docIds.add(identity);
            }
        }
        return docIds.size() <= 1 ? ChatRouteEnum.SCOPED_RAG : ChatRouteEnum.HYBRID_RAG;
    }

    /** 证据文档身份: 优先申请号/公布号(chunkMetadata), 缺失回退文档名; 空证据返回 null */
    private String evidenceDocIdentity(ChatSendResult.EvidenceSummary e) {
        if (e == null) {
            return null;
        }
        if (StrUtil.isNotBlank(e.getChunkMetadata())) {
            try {
                cn.hutool.json.JSONObject meta = JSONUtil.parseObj(e.getChunkMetadata());
                String applicationNo = meta.getStr("applicationNo");
                if (StrUtil.isNotBlank(applicationNo)) {
                    return "app:" + applicationNo;
                }
                String publicationNo = meta.getStr("publicationNo");
                if (StrUtil.isNotBlank(publicationNo)) {
                    return "pub:" + publicationNo;
                }
            } catch (Exception ignored) {
                // 元数据解析失败回退文档名
            }
        }
        return e.getDocumentName();
    }

    private ChatSendResult buildTransferResult(AiConversationDO conversation, KnowledgeContext knowledgeContext,
                                               String message, EvidenceEvaluateRespDTO resp) {
        String transferReason = deriveTransferReason(resp);
        String summary = buildSummaryDraft(message, transferReason, resp);
        return ChatSendResult.builder()
                .conversationId(conversation.getId())
                .kbId(knowledgeContext.kbId())
                .domainCode(knowledgeContext.domainCode())
                .intent(resolveIntent(resp))
                .route(ChatRouteEnum.ABSTAIN)
                .answer(null)
                .answerable(false)
                .confidence(resp != null ? resp.getConfidence() : null)
                .citations(resp != null ? buildCitations(resp) : null)
                .traceId(resp != null ? resp.getTraceId() : null)
                .transferRequired(true)
                .transferReason(transferReason)
                .summary(summary)
                .degraded(isDegraded(resp))
                .build();
    }

    private ChatSendResult buildManualTransferResult(AiConversationDO conversation, KnowledgeContext knowledgeContext,
                                                     String message, String reason) {
        return ChatSendResult.builder()
                .conversationId(conversation.getId())
                .kbId(knowledgeContext.kbId())
                .domainCode(knowledgeContext.domainCode())
                .route(ChatRouteEnum.ABSTAIN)
                .answer(null)
                .answerable(false)
                .confidence(null)
                .citations(null)
                .traceId(null)
                .transferRequired(true)
                .transferReason(reason)
                .summary(transferHandler.buildSummary(message, reason, null, null))
                .degraded(false)
                .build();
    }

    private ChatSendResult buildKbRequiredResult(Long conversationId) {
        return ChatSendResult.builder()
                .conversationId(conversationId)
                .answer("请先选择要查询的知识库(专利 MVP 一次选择一个), 再发送问题。")
                .answerable(false)
                .transferRequired(false)
                .degraded(false)
                .route(ChatRouteEnum.ABSTAIN)
                .build();
    }

    private ChatSendResult buildClosedResult(Long conversationId) {
        return ChatSendResult.builder()
                .conversationId(conversationId)
                .answer(null)
                .answerable(false)
                .confidence(null)
                .citations(null)
                .traceId(null)
                .transferRequired(true)
                .transferReason(TransferHandler.REASON_CLOSED)
                .summary(null)
                .degraded(false)
                .route(ChatRouteEnum.ABSTAIN)
                .build();
    }

    private String resolveIntent(EvidenceEvaluateRespDTO resp) {
        return resp != null && resp.getAnalysis() != null ? resp.getAnalysis().getIntent() : null;
    }

    private boolean isDegraded(EvidenceEvaluateRespDTO resp) {
        return resp == null || Boolean.TRUE.equals(resp.getClaimFail());
    }

    private List<ChatTurnDTO> buildHistory(List<AiMessageDO> recent) {
        if (CollUtil.isEmpty(recent)) return List.of();
        List<ChatTurnDTO> history = new ArrayList<>(recent.size());
        for (AiMessageDO message : recent) {
            if ("USER".equals(message.getRole())) {
                history.add(buildTurn("USER", message.getContent()));
            } else if ("AI".equals(message.getRole())) {
                history.add(buildTurn("AI", message.getContent()));
            }
        }
        return history;
    }

    private ChatTurnDTO buildTurn(String role, String content) {
        ChatTurnDTO turn = new ChatTurnDTO();
        turn.setRole(role);
        turn.setContent(content);
        return turn;
    }

    private List<Long> buildCitations(EvidenceEvaluateRespDTO resp) {
        List<Long> citations = new ArrayList<>();
        if (resp == null || resp.getEvidence() == null) {
            return citations;
        }
        Set<Long> seen = new HashSet<>();
        if (resp.getClaims() != null) {
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
        }
        // 降级/无断言路径(如验证器解析故障降级信任生成): claims 为空但回答仍标注了 [C1]..[CN],
        // 从回答文本提取引用编号映射到 evidence 位置, 保证来源卡片与回答标注一致
        if (citations.isEmpty() && StrUtil.isNotBlank(resp.getAnswer())) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[C(\\d+)]").matcher(resp.getAnswer());
            while (m.find()) {
                int index = Integer.parseInt(m.group(1)) - 1;
                if (index < 0 || index >= resp.getEvidence().size()) {
                    continue;
                }
                EvidenceItemDTO item = resp.getEvidence().get(index);
                if (item != null && item.getChunkId() != null && seen.add(item.getChunkId())) {
                    citations.add(item.getChunkId());
                }
            }
        }
        return citations;
    }

    private String deriveTransferReason(EvidenceEvaluateRespDTO resp) {
        if (resp == null) return REASON_EVAL_UNAVAILABLE;
        String refusalReason = resp.getRefusalReason();
        if (StrUtil.isNotBlank(refusalReason)) {
            if (refusalReason.contains("超出")) return REASON_OUT_OF_SCOPE;
            if (refusalReason.contains("冲突")) return REASON_CONFLICT;
            if (refusalReason.contains("产品不匹配")) return REASON_PRODUCT_MISMATCH;
            if (refusalReason.contains("证据不足") || refusalReason.contains("未检索")) return REASON_INSUFFICIENT;
            if (refusalReason.contains("阻断") || refusalReason.contains("拒绝作答")) return REASON_BLOCKED;
        }
        if (Boolean.TRUE.equals(resp.getClaimFail())) return REASON_CLAIM_FAIL;
        return REASON_FALLBACK;
    }

    private String buildSummaryDraft(String message, String transferReason, EvidenceEvaluateRespDTO resp) {
        StringBuilder summary = new StringBuilder("客户问题: ").append(message)
                .append(" | 原因: ").append(transferReason);
        if (resp != null && StrUtil.isNotBlank(resp.getAnswer())) {
            summary.append(" | AI建议: ").append(StrUtil.maxLength(resp.getAnswer(), 100));
        }
        return summary.toString();
    }

    private BigDecimal toConfidence(Double confidence) {
        if (confidence == null) return null;
        return BigDecimal.valueOf(confidence).setScale(4, RoundingMode.HALF_UP);
    }
}
