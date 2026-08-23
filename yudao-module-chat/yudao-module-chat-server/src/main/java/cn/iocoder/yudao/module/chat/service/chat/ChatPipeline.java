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

import static cn.iocoder.yudao.module.chat.enums.ErrorCodeConstants.CONVERSATION_CONTEXT_STALE;
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
    @Resource
    private cn.iocoder.yudao.module.chat.service.trace.QueryTraceService queryTraceService;

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
        // P0-09: 每个用户问题一个主 traceId(q- 前缀), 贯穿检索/证据全链路
        String traceId = queryTraceService.newTraceId();
        // AG-10: trace 总耗时使用整个 Query 墙钟(queryStart → final result), 不依赖 result.latencyMs(在 send() 才赋值)
        long traceStartMs = System.currentTimeMillis();

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
                // RF-07: 无绑定历史会话只读历史, 继续发送由前端新建会话; 禁止回退旧 kb_ids 全库检索
                log.info("[send][会话({}) 未绑定知识库, 拒绝继续查询: {}]", conversationId, message);
                return buildKbRequiredResult(conversationId);
            }
            // RF-01: 每轮重新校验当前用户对该绑定 KB 的可见性(权限被撤销则拒绝继续)
            ensureKbVisible(persistedKbId, userId);
            // RF2-01: 查询当前 KB 领域并与会话创建时快照对比, 不一致拒绝继续(禁止静默修改/降级 GENERAL)
            String currentDomain = resolveCurrentDomain(persistedKbId);
            String snapshotDomain = conversation.getDomainCode();
            if (StrUtil.isNotBlank(snapshotDomain) && !snapshotDomain.equals(currentDomain)) {
                log.info("[send][会话({}) 知识库领域由 {} 变为 {}, 拒绝继续: {}]",
                        conversationId, snapshotDomain, currentDomain, message);
                throw new ServiceException(CONVERSATION_CONTEXT_STALE);
            }
            knowledgeContext = new KnowledgeContext(persistedKbId, currentDomain);
            effectiveKbIds = List.of(knowledgeContext.kbId());
        }

        // P0-09: 创建 Query Trace 主记录(仅正常可查询路径; 拒绝/关闭请求不落 trace)
        queryTraceService.begin(traceId, conversationId, message,
                knowledgeContext.kbId(), knowledgeContext.domainCode());

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

        // 6. 证据判定(P0-09: 透传统一主 traceId)。
        EvidenceEvaluateRespDTO resp = evidenceRpcAdapter.evaluate(message, tenantId, userId, null, history,
                effectiveKbIds, traceId);

        ChatSendResult result;
        if (isClarifyRequired(resp)) {
            result = buildClarifyResult(conversation, knowledgeContext, resp);
        } else if (isAnswerable(resp)) {
            result = buildAnswerResult(conversation, knowledgeContext, resp);
        } else if (resp != null && Boolean.TRUE.equals(resp.getTimedOut())) {
            // P0-11: 查询超时 → 降级结果(非转人工), 返回可理解提示
            result = buildDegradedResult(conversation, knowledgeContext, resp,
                    "本次查询超时，未能完成可靠回答，请稍后重试或调整问题。");
        } else if (resp != null && Boolean.TRUE.equals(resp.getVerificationDegraded())) {
            // P0-11: 验证降级且无可用回答 → 降级结果(非转人工)
            result = buildDegradedResult(conversation, knowledgeContext, resp,
                    "当前知识库中没有足够证据支持可靠回答。");
        } else {
            result = transferHandler.handleTransfer(conversationId, message,
                    buildTransferResult(conversation, knowledgeContext, message, resp));
        }

        // P0-09: 落库全链路阶段 + 完成 Query Trace
        if (resp != null) {
            queryTraceService.recordStages(traceId, resp.getStages());
        }
        if (result != null) {
            queryTraceService.finish(traceId, result.getRoute(),
                    System.currentTimeMillis() - traceStartMs,
                    deriveTraceStatus(resp, result));
        }
        return result;
    }

    /** P0-09: 由响应与结果推导 Trace 终态(SUCCEEDED/DEGRADED/FAILED) */
    private String deriveTraceStatus(EvidenceEvaluateRespDTO resp, ChatSendResult result) {
        if (Boolean.TRUE.equals(resp != null ? resp.getTimedOut() : null)) return "TIMEOUT";
        if (Boolean.TRUE.equals(result.getDegraded())) return "DEGRADED";
        if (Boolean.TRUE.equals(result.getTransferRequired())) return "DEGRADED";
        return "SUCCEEDED";
    }

    private KnowledgeContext resolveKnowledgeContext(Long kbId, Long userId) {
        ensureKbVisible(kbId, userId);
        return new KnowledgeContext(kbId, resolveCurrentDomain(kbId));
    }

    /** 查询当前 KB 领域(fail-closed: RPC 失败 / null / blank → KNOWLEDGE_DOMAIN_UNAVAILABLE) */
    private String resolveCurrentDomain(Long kbId) {
        CommonResult<Map<Long, String>> domainResult = knowledgeApi.getKbDomainCodes(List.of(kbId));
        if (domainResult == null || !domainResult.isSuccess() || domainResult.getData() == null
                || StrUtil.isBlank(domainResult.getData().get(kbId))) {
            throw new ServiceException(
                    cn.iocoder.yudao.module.chat.enums.ErrorCodeConstants.KNOWLEDGE_DOMAIN_UNAVAILABLE);
        }
        return domainResult.getData().get(kbId);
    }

    /** 校验当前用户对该知识库的可见性(失败即抛出, 不降级) */
    private void ensureKbVisible(Long kbId, Long userId) {
        if (kbId == null || kbId <= 0) {
            throw new ServiceException(cn.iocoder.yudao.module.chat.enums.ErrorCodeConstants.KNOWLEDGE_BASE_NOT_EXISTS);
        }
        CommonResult<Set<Long>> visibleResult = knowledgeApi.getVisibleKbIds(userId);
        if (visibleResult == null || !visibleResult.isSuccess() || visibleResult.getData() == null
                || !visibleResult.getData().contains(kbId)) {
            throw new ServiceException(cn.iocoder.yudao.module.chat.enums.ErrorCodeConstants.KNOWLEDGE_BASE_NOT_EXISTS);
        }
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

    /** P0-11: 降级结果(查询超时/验证降级): 非转人工, 返回可理解提示并落库 AI 消息 */
    private ChatSendResult buildDegradedResult(AiConversationDO conversation, KnowledgeContext knowledgeContext,
                                               EvidenceEvaluateRespDTO resp, String message) {
        AiMessageDO aiMessage = messageService.addMessage(conversation.getId(), "AI", message,
                null, null, null, null, resp != null ? resp.getTraceId() : null);
        return ChatSendResult.builder()
                .conversationId(conversation.getId())
                .messageId(aiMessage != null ? aiMessage.getId() : null)
                .kbId(knowledgeContext.kbId())
                .domainCode(knowledgeContext.domainCode())
                .route(ChatRouteEnum.ABSTAIN)
                .intent(resolveIntent(resp))
                .degraded(true)
                .answer(message)
                .answerable(false)
                .confidence(resp != null ? resp.getConfidence() : null)
                .citations(List.of())
                .traceId(resp != null ? resp.getTraceId() : null)
                .transferRequired(false)
                .build();
    }

    private ChatSendResult buildAnswerResult(AiConversationDO conversation, KnowledgeContext knowledgeContext,
                                             EvidenceEvaluateRespDTO resp) {
        List<Long> citations = buildCitations(resp);
        List<ChatSendResult.EvidenceSummary> evidence = buildEvidenceSummaries(resp,
                knowledgeContext.kbId(), knowledgeContext.domainCode());
        AiMessageDO aiMessage = messageService.addMessage(conversation.getId(), "AI", resp.getAnswer(),
                JSONUtil.toJsonStr(citations), null, null,
                toConfidence(resp.getConfidence()), resp.getTraceId());
        // P0-08: 证据快照随消息落库(历史会话刷新后 Evidence 不丢; 快照为当时版本, 不随知识升级漂移)
        if (aiMessage != null) {
            persistMessageEvidence(aiMessage.getId(), resp, knowledgeContext.kbId(), knowledgeContext.domainCode());
        }
        return ChatSendResult.builder()
                .conversationId(conversation.getId())
                .messageId(aiMessage != null ? aiMessage.getId() : null)
                .kbId(knowledgeContext.kbId())
                .domainCode(knowledgeContext.domainCode())
                .route(resolveRoute(resp))
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

    private List<ChatSendResult.EvidenceSummary> buildEvidenceSummaries(EvidenceEvaluateRespDTO resp,
                                                                         Long kbId, String domainCode) {
        if (resp == null || resp.getEvidence() == null || resp.getEvidence().isEmpty()) return List.of();
        List<ChatSendResult.EvidenceSummary> list = new ArrayList<>();
        for (EvidenceItemDTO e : resp.getEvidence()) {
            if (e == null || e.getChunkId() == null) continue;
            list.add(ChatSendResult.EvidenceSummary.builder()
                    .evidenceId(e.getEvidenceId() != null ? e.getEvidenceId() : e.getChunkId())
                    .chunkId(e.getChunkId())
                    .documentId(e.getDocumentId())
                    .documentName(e.getDocumentName())
                    .versionId(e.getVersionId())
                    .versionNo(e.getVersionNo())
                    .kbId(e.getKbId() != null ? e.getKbId() : kbId)
                    .domainCode(e.getDomainCode() != null ? e.getDomainCode() : domainCode)
                    .sectionType(e.getSectionType())
                    .sectionTitle(e.getSectionTitle())
                    .claimNo(e.getClaimNo())
                    .pageStart(e.getPageStart())
                    .pageEnd(e.getPageEnd())
                    .applicationNo(e.getApplicationNo())
                    .publicationNo(e.getPublicationNo())
                    .content(e.getContent() == null ? null : StrUtil.sub(e.getContent(), 0, 500))
                    .score(e.getScore())
                    .evidenceType(e.getEvidenceType())
                    .metric(e.getMetric())
                    .aggregateValue(e.getAggregateValue())
                    .filters(e.getFilters())
                    .build());
        }
        return list;
    }

    /**
     * P0-08: 将证据评估响应中的证据列表落库为消息证据快照(ai_message_evidence)。
     * 快照携带 文档/版本/片段/申请号/公布号/原文, 历史会话刷新后仍可还原"当时回答依据 V1"。
     */
    private void persistMessageEvidence(Long messageId, EvidenceEvaluateRespDTO resp, Long kbId, String domainCode) {
        if (resp == null || resp.getEvidence() == null || resp.getEvidence().isEmpty()) return;
        List<cn.iocoder.yudao.module.chat.dal.dataobject.message.AiMessageEvidenceDO> rows = new ArrayList<>();
        int index = 0;
        for (EvidenceItemDTO e : resp.getEvidence()) {
            if (e == null || e.getChunkId() == null) continue;
            rows.add(toEvidenceSnapshot(messageId, index, e, kbId, domainCode));
            index++;
        }
        if (!rows.isEmpty()) {
            messageService.addMessageEvidence(messageId, rows);
        }
    }

    private cn.iocoder.yudao.module.chat.dal.dataobject.message.AiMessageEvidenceDO toEvidenceSnapshot(
            Long messageId, int index, EvidenceItemDTO e, Long kbId, String domainCode) {
        cn.iocoder.yudao.module.chat.dal.dataobject.message.AiMessageEvidenceDO row =
                new cn.iocoder.yudao.module.chat.dal.dataobject.message.AiMessageEvidenceDO();
        row.setMessageId(messageId);
        row.setEvidenceIndex(index);
        row.setCitationLabel("C" + (index + 1));
        row.setDocumentId(e.getDocumentId());
        row.setVersionId(e.getVersionId());
        row.setChunkId(e.getChunkId());
        row.setKbId(e.getKbId() != null ? e.getKbId() : kbId);
        row.setDomainCode(e.getDomainCode() != null ? e.getDomainCode() : domainCode);
        row.setSectionType(e.getSectionType());
        row.setSectionTitle(e.getSectionTitle());
        row.setClaimNo(e.getClaimNo());
        row.setPageStart(e.getPageStart());
        row.setPageEnd(e.getPageEnd());
        row.setApplicationNo(e.getApplicationNo());
        row.setPublicationNo(e.getPublicationNo());
        row.setDocumentName(e.getDocumentName());
        row.setVersionNo(e.getVersionNo());
        row.setContentSnapshot(e.getContent() == null ? null : StrUtil.sub(e.getContent(), 0, 2000));
        row.setMetadataSnapshot(e.getChunkMetadata());
        row.setScore(e.getScore() == null ? null : BigDecimal.valueOf(e.getScore()).setScale(4, RoundingMode.HALF_UP));
        return row;
    }

    /**
     * 路由(不再由 Chat 自行推断): 不可作答/评估缺失 → ABSTAIN; 可作答时透传 Query Planner
     * 的权威 route(RULE/EXACT_METADATA/EXACT_CLAIM/SCOPED_RAG/HYBRID_RAG)。
     * 保证正式 response 的 route 永不为 null(RF2-06)。
     */
    private String resolveRoute(EvidenceEvaluateRespDTO resp) {
        if (resp == null || !Boolean.TRUE.equals(resp.getAnswerable())) {
            return ChatRouteEnum.ABSTAIN;
        }
        return StrUtil.isBlank(resp.getRoute()) ? ChatRouteEnum.ABSTAIN : resp.getRoute();
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
        if (resp == null) return null;
        if (StrUtil.isNotBlank(resp.getIntent())) return resp.getIntent(); // KB_STATISTICS 等确定性路径
        return resp.getAnalysis() != null ? resp.getAnalysis().getIntent() : null;
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
