package cn.iocoder.yudao.module.chat.service.chat;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.chat.channel.ChannelAdapter;
import cn.iocoder.yudao.module.chat.controller.admin.chat.vo.ChatStreamEvent;
import cn.iocoder.yudao.module.chat.dal.dataobject.conversation.AiConversationDO;
import cn.iocoder.yudao.module.chat.dal.dataobject.message.AiMessageDO;
import cn.iocoder.yudao.module.chat.enums.chat.ChatRouteEnum;
import cn.iocoder.yudao.module.chat.enums.chat.QueryStageEnum;
import cn.iocoder.yudao.module.chat.enums.conversation.ConversationStatusEnum;
import cn.iocoder.yudao.module.chat.framework.chat.ChatProperties;
import cn.iocoder.yudao.module.chat.service.conversation.ConversationService;
import cn.iocoder.yudao.module.chat.service.evidence.EvidenceRpcAdapter;
import cn.iocoder.yudao.module.chat.service.message.MessageService;
import cn.iocoder.yudao.module.chat.service.trace.QueryTraceService;
import cn.iocoder.yudao.module.chat.service.transfer.TransferHandler;
import cn.iocoder.yudao.module.evidence.api.dto.ChatTurnDTO;
import cn.iocoder.yudao.module.evidence.api.dto.EvidenceClaimDTO;
import cn.iocoder.yudao.module.evidence.api.dto.EvidenceEvaluateRespDTO;
import cn.iocoder.yudao.module.evidence.api.dto.EvidenceItemDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.QueryStageTimingDTO;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import cn.iocoder.yudao.module.retrieval.api.dto.QueryStageTimingDTO;
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

import static cn.iocoder.yudao.module.chat.enums.ErrorCodeConstants.CONVERSATION_CONTEXT_STALE;
import static cn.iocoder.yudao.module.chat.enums.ErrorCodeConstants.CONVERSATION_NOT_EXISTS;

/** 对话编排管线: 会话 → 证据判定 → 回答或转人工; 支持同步 send 与 SSE 流式 stream。 */
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
    private QueryTraceService queryTraceService;

    public ChatSendResult send(Long conversationId, String message, String channel, String customerId) {
        return send(conversationId, message, channel, customerId, (Long) null);
    }

    public ChatSendResult send(Long conversationId, String message, String channel, String customerId,
                               Long kbId) {
        long startNanos = System.nanoTime();
        ChatSendResult result = doSend(conversationId, message, channel, customerId, kbId, null);
        if (result != null) {
            result.setLatencyMs((int) ((System.nanoTime() - startNanos) / 1_000_000));
        }
        return result;
    }

    /**
     * 流式问答(SSE): 复用 {@link #send} 同一条 Query Pipeline 语义, 仅额外向 {@code sink} 输出
     * conversation/stage/evidence/delta/verification/done 事件。业务异常 → error 事件。
     * done 事件在 {@link #doSend} 内发出; 本方法只负责兜底异常转 error。
     */
    public void stream(Long conversationId, String message, String channel, String customerId, Long kbId,
                       ChatStreamSink sink) {
        long startNanos = System.nanoTime();
        try {
            ChatSendResult result = doSend(conversationId, message, channel, customerId, kbId, sink);
            if (result != null && result.getLatencyMs() == null) {
                result.setLatencyMs((int) ((System.nanoTime() - startNanos) / 1_000_000));
            }
        } catch (ServiceException e) {
            log.warn("[stream][会话({}) 业务错误: code={}, msg={}]", conversationId, e.getCode(), e.getMessage());
            emitError(sink, String.valueOf(e.getCode()), e.getMessage(), isRetryableError(e.getCode()), null);
        } catch (Exception e) {
            log.error("[stream][会话({}) 系统异常]", conversationId, e);
            emitError(sink, "INTERNAL", "系统繁忙，请稍后重试", false, null);
        }
    }

    /**
     * 发送消息。知识库上下文由新会话请求或既有会话的持久化绑定决定。
     *
     * @param sink 流式事件输出通道; 同步 {@code /send} 传 null 保持原行为
     */
    private ChatSendResult doSend(Long conversationId, String message, String channel, String customerId,
                                  Long kbId, ChatStreamSink sink) {
        String resolvedChannel = resolveChannel(channel);
        LoginUser loginUser = SecurityFrameworkUtils.getLoginUser();
        Long tenantId = loginUser != null ? loginUser.getTenantId() : null;
        Long userId = loginUser != null ? loginUser.getId() : null;
        // P0-09: 每个用户问题一个主 traceId(q- 前缀), 贯穿检索/证据全链路
        String traceId = queryTraceService.newTraceId();
        // AG-10: trace 总耗时使用整个 Query 墙钟(queryStart → final result), 不依赖 result.latencyMs(在 send() 才赋值)
        long traceStartMs = System.currentTimeMillis();
        // SQ-10: 管线级阶段归因(定位"阶段总和远小于 total latency"的未归因耗时)
        long t0 = traceStartMs;
        long tConv;
        long tCtx;
        long tRpc0;
        long tRpc1;
        long tResult;

        // P0-08: 连接建立即输出首个阶段(让用户在 100~500ms 内感知系统已开始处理)
        if (sink != null) {
            emitStage(sink, QueryStageEnum.ANALYZE.getCode(), "RUNNING", "正在理解问题", 0L, null, null, null, null);
        }

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
                return emitDoneAndReturn(buildClosedResult(conversationId), traceId, sink, traceStartMs);
            }
            Long persistedKbId = conversation.getKbId();
            if (persistedKbId == null) {
                // RF-07: 无绑定历史会话只读历史, 继续发送由前端新建会话; 禁止回退旧 kb_ids 全库检索
                log.info("[send][会话({}) 未绑定知识库, 拒绝继续查询: {}]", conversationId, message);
                return emitDoneAndReturn(buildKbRequiredResult(conversationId), traceId, sink, traceStartMs);
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
        // SQ-10: 会话解析(含权限校验 + 领域解析)阶段归因
        tConv = System.currentTimeMillis();

        // P0-08: 会话上下文就绪 → 输出 conversation + 粗粒度阶段(已理解问题/已锁定知识库)
        if (sink != null) {
            if (sink.isCancelled()) {
                // SSE-03: 客户端取消属于正常终态, 返回 null 由 Controller 幂等 complete 收尾(不再二次 complete)
                log.info("[stream][traceId({}) 客户端取消, 中止]", traceId);
                return null;
            }
            emitConversation(sink, conversationId, traceId, knowledgeContext);
            emitStage(sink, QueryStageEnum.ANALYZE.getCode(), "DONE", "已理解问题", 0L, null, null, null, null);
            emitStage(sink, QueryStageEnum.SCOPE_FILTER.getCode(), "DONE", "已锁定知识库", 0L, null,
                    "kbId=" + knowledgeContext.kbId() + ", domain=" + knowledgeContext.domainCode(), null, null);
        }

        // P0-09: 创建 Query Trace 主记录(仅正常可查询路径; 拒绝/关闭请求不落 trace)
        queryTraceService.begin(traceId, conversationId, message,
                knowledgeContext.kbId(), knowledgeContext.domainCode());

        // 3. USER 落库前读取历史，天然排除当前轮。
        List<ChatTurnDTO> history = buildHistory(messageService.getRecentMessages(
                conversationId, chatProperties.getMaxContextMessages()));

        // 4. USER 消息落库。
        messageService.addMessage(conversationId, "USER", message, null, null, null, null, null, null, null);

        // 5. 转人工早检。
        String manualReason = transferHandler.detectTransferReason(message);
        if (manualReason != null) {
            return emitDoneAndReturn(transferHandler.handleTransfer(conversationId, message,
                    buildManualTransferResult(conversation, knowledgeContext, message, manualReason)),
                    traceId, sink, traceStartMs);
        }
        // SQ-10: 上下文组装(历史加载 + USER 消息落库 + 转人工早检)阶段归因
        tCtx = System.currentTimeMillis();

        // P0-08: 进入证据判定前输出 EVIDENCE RUNNING(同步 RPC 阻塞期间用户看到"正在检索/生成")
        if (sink != null) {
            if (sink.isCancelled()) {
                // SSE-03: 客户端取消属于正常终态; Query Trace 终态记为 CLIENT_CANCELLED(SSE-08)
                log.info("[stream][traceId({}) 客户端取消, 中止]", traceId);
                queryTraceService.finish(traceId, null, System.currentTimeMillis() - traceStartMs, "CLIENT_CANCELLED");
                return null;
            }
            emitStage(sink, QueryStageEnum.EVIDENCE.getCode(), "RUNNING", "正在评估证据、检索知识", 0L, null, null, null, null);
        }

        // 6. 证据判定(P0-09: 透传统一主 traceId; SQ-10: 会话绑定 KB 领域透传供 Structured Query 路由)。
        tRpc0 = System.currentTimeMillis();
        EvidenceEvaluateRespDTO resp = evidenceRpcAdapter.evaluate(message, tenantId, userId, null, history,
                effectiveKbIds, traceId, knowledgeContext.domainCode());
        tRpc1 = System.currentTimeMillis();

        // P0-08: RPC 返回后若客户端已断开, 不再生成/落库 AI 消息(USER 消息已保留)
        if (sink != null && sink.isCancelled()) {
            log.info("[stream][traceId({}) 客户端取消, 不再生成/落库]", traceId);
            queryTraceService.finish(traceId, null, System.currentTimeMillis() - traceStartMs, "CLIENT_CANCELLED");
            return null;
        }

        ChatSendResult result;
        if (isClarifyRequired(resp)) {
            result = buildClarifyResult(conversation, knowledgeContext, resp, traceId);
        } else if (isAnswerable(resp)) {
            result = buildAnswerResult(conversation, knowledgeContext, resp, traceId);
        } else if (resp != null && Boolean.TRUE.equals(resp.getTimedOut())) {
            // P0-11: 查询超时 → 降级结果(非转人工), 返回可理解提示
            result = buildDegradedResult(conversation, knowledgeContext, resp,
                    "本次查询超时，未能完成可靠回答，请稍后重试或调整问题。", traceId);
        } else if (resp != null && Boolean.TRUE.equals(resp.getVerificationDegraded())) {
            // P0-11: 验证降级且无可用回答 → 降级结果(非转人工)
            result = buildDegradedResult(conversation, knowledgeContext, resp,
                    "当前知识库中没有足够证据支持可靠回答。", traceId);
        } else {
            result = transferHandler.handleTransfer(conversationId, message,
                    buildTransferResult(conversation, knowledgeContext, message, resp));
        }
        // SQ-10: 结果组装(AI 消息落库 + 证据快照)阶段归因
        tResult = System.currentTimeMillis();

        // P0-08: 流式输出最终执行过程与答案(与 Query Trace 同源: 阶段均来自 resp.getStages())
        if (sink != null && result != null) {
            replayStages(sink, resp, traceId);
            emitEvidenceEvent(sink, result);
            emitVerificationEvent(sink, resp, traceId);
            emitDelta(sink, result);
            result.setQueryTraceId(traceId);
            result.setLatencyMs((int) (System.currentTimeMillis() - traceStartMs));
            emitDone(sink, result, traceId);
        }

        // P0-09: 落库全链路阶段 + 完成 Query Trace
        // SQ-10: 汇聚管线级阶段(含 RPC/持久化归因) + 证据侧阶段, 定位"阶段总和 << total latency"缺口
        if (resp != null) {
            queryTraceService.recordStages(traceId, mergePipelineStages(
                    t0, tConv, tCtx, tRpc0, tRpc1, tResult, System.currentTimeMillis(), resp.getStages()));
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

    /**
     * SQ-10: 汇聚管线级阶段与会话侧阶段, 使 Trace 阶段总和 ≈ total latency(定位未归因耗时)。
     * 阶段归因口径: CONVERSATION_LOAD(含权限校验/领域解析) / CONTEXT_RESOLVE(历史+USER落库+转人工早检)
     * / RPC(证据评估) / MESSAGE_PERSIST(AI 落库+证据快照) / TRACE_PERSIST(Query Trace 落库)。
     */
    private List<QueryStageTimingDTO> mergePipelineStages(long t0, long tConv, long tCtx, long tRpc0,
                                                          long tRpc1, long tResult, long tFinish,
                                                          List<QueryStageTimingDTO> evidenceStages) {
        List<QueryStageTimingDTO> stages = new ArrayList<>();
        int seq = 0;
        stages.add(pipelineStage("PIPELINE_ENTER", ++seq, 0, "SUCCEEDED"));
        stages.add(pipelineStage("CONVERSATION_LOAD", ++seq, Math.max(0, tConv - t0), "SUCCEEDED"));
        stages.add(pipelineStage("PERMISSION_CHECK", ++seq, 0, "SUCCEEDED")); // 并入 CONVERSATION_LOAD
        stages.add(pipelineStage("CONTEXT_RESOLVE", ++seq, Math.max(0, tCtx - tConv), "SUCCEEDED"));
        stages.add(pipelineStage("RPC", ++seq, Math.max(0, tRpc1 - tRpc0), "SUCCEEDED"));
        // 证据侧内部阶段(RPC 内部分解: 检索/生成或 Structured Query 阶段)
        if (evidenceStages != null) {
            for (QueryStageTimingDTO s : evidenceStages) {
                if (s == null) continue;
                s.setSeq(++seq);
                stages.add(s);
            }
        }
        stages.add(pipelineStage("MESSAGE_PERSIST", ++seq, Math.max(0, tResult - tRpc1), "SUCCEEDED"));
        stages.add(pipelineStage("TRACE_PERSIST", ++seq, Math.max(0, tFinish - tResult), "SUCCEEDED"));
        stages.add(pipelineStage("PIPELINE_EXIT", ++seq, 0, "SUCCEEDED"));
        return stages;
    }

    private QueryStageTimingDTO pipelineStage(String stage, int seq, long elapsedMs, String status) {
        QueryStageTimingDTO dto = new QueryStageTimingDTO();
        dto.setStage(stage);
        dto.setSeq(seq);
        dto.setStatus(status);
        dto.setElapsedMs(elapsedMs);
        dto.setSkipped(false);
        return dto;
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
        if (resp == null) return false;
        // 槽位反问(缺必填槽位) 或 结构化反问(CLARIFY 路由: scope/metric/operation 无法消解)
        boolean slotClarify = resp.getMissingSlots() != null && !resp.getMissingSlots().isEmpty();
        boolean structuredClarify = ChatRouteEnum.CLARIFY.equals(resp.getRoute());
        return (slotClarify || structuredClarify) && StrUtil.isNotBlank(resp.getClarifyQuestion());
    }

    private ChatSendResult buildClarifyResult(AiConversationDO conversation, KnowledgeContext knowledgeContext,
                                              EvidenceEvaluateRespDTO resp, String queryTraceId) {
        String route = ChatRouteEnum.CLARIFY.equals(resp.getRoute()) ? ChatRouteEnum.CLARIFY : ChatRouteEnum.ABSTAIN;
        AiMessageDO aiMessage = messageService.addMessage(conversation.getId(), "AI", resp.getClarifyQuestion(),
                null, null, null, null, resp.getTraceId(), queryTraceId, route);
        return ChatSendResult.builder()
                .conversationId(conversation.getId())
                .messageId(aiMessage != null ? aiMessage.getId() : null)
                .kbId(knowledgeContext.kbId())
                .domainCode(knowledgeContext.domainCode())
                .intent(resolveIntent(resp))
                .route(route)
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
                                               EvidenceEvaluateRespDTO resp, String message, String queryTraceId) {
        AiMessageDO aiMessage = messageService.addMessage(conversation.getId(), "AI", message,
                null, null, null, null, resp != null ? resp.getTraceId() : null, queryTraceId, ChatRouteEnum.ABSTAIN);
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
                                             EvidenceEvaluateRespDTO resp, String queryTraceId) {
        List<Long> citations = buildCitations(resp);
        List<ChatSendResult.EvidenceSummary> evidence = buildEvidenceSummaries(resp,
                knowledgeContext.kbId(), knowledgeContext.domainCode());
        AiMessageDO aiMessage = messageService.addMessage(conversation.getId(), "AI", resp.getAnswer(),
                JSONUtil.toJsonStr(citations), null, null,
                toConfidence(resp.getConfidence()), resp.getTraceId(), queryTraceId, resolveRoute(resp));
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

    // ==================== P0-08 流式事件输出 ====================

    /** 返回前输出 done 事件(仅流式路径; 同步路径直接返回) */
    private ChatSendResult emitDoneAndReturn(ChatSendResult result, String traceId, ChatStreamSink sink, long traceStartMs) {
        if (sink != null && result != null) {
            result.setQueryTraceId(traceId);
            result.setLatencyMs((int) (System.currentTimeMillis() - traceStartMs));
            emitDone(sink, result, traceId);
        }
        return result;
    }

    private void emitConversation(ChatStreamSink sink, Long conversationId, String traceId, KnowledgeContext ctx) {
        if (sink == null) return;
        emitIfPresent(sink, ChatStreamEvent.builder()
                .type(ChatStreamEvent.TYPE_CONVERSATION)
                .conversationId(conversationId)
                .queryId(traceId)
                .traceId(traceId)
                .kbId(ctx.kbId())
                .domainCode(ctx.domainCode())
                .build());
    }

    private void emitStage(ChatStreamSink sink, String stage, String status, String label, Long elapsedMs,
                           String inputSummary, String outputSummary, String errorCode, String modelCallId) {
        if (sink == null) return;
        emitIfPresent(sink, ChatStreamEvent.builder()
                .type(ChatStreamEvent.TYPE_STAGE)
                .stage(stage)
                .status(status)
                .label(label)
                .elapsedMs(elapsedMs)
                .inputSummary(inputSummary)
                .outputSummary(outputSummary)
                .errorCode(errorCode)
                .modelCallId(modelCallId)
                .build());
    }

    /** 重放权威阶段(与 Query Trace 同源, 均来自 resp.getStages()) */
    private void replayStages(ChatStreamSink sink, EvidenceEvaluateRespDTO resp, String traceId) {
        if (sink == null || resp == null || resp.getStages() == null) return;
        for (QueryStageTimingDTO s : resp.getStages()) {
            if (s == null) continue;
            QueryStageEnum se = QueryStageEnum.of(s.getStage());
            emitStage(sink, s.getStage(), mapStageStatus(s.getStatus()),
                    se != null ? se.getLabel() : s.getStage(),
                    s.getElapsedMs(), s.getInputSummary(), s.getOutputSummary(), s.getErrorCode(), s.getModelCallId());
        }
    }

    private String mapStageStatus(String status) {
        if (status == null) return "DONE";
        return switch (status) {
            case "SUCCEEDED" -> "DONE";
            case "FAILED" -> "FAILED";
            case "SKIPPED" -> "SKIPPED";
            default -> status;
        };
    }

    private void emitEvidenceEvent(ChatStreamSink sink, ChatSendResult result) {
        if (sink == null || result == null || CollUtil.isEmpty(result.getEvidence())) return;
        emitIfPresent(sink, ChatStreamEvent.builder()
                .type(ChatStreamEvent.TYPE_EVIDENCE)
                .count(result.getEvidence().size())
                .items(result.getEvidence())
                .build());
    }

    private void emitVerificationEvent(ChatStreamSink sink, EvidenceEvaluateRespDTO resp, String traceId) {
        if (sink == null) return;
        boolean fail = resp != null && (Boolean.TRUE.equals(resp.getClaimFail())
                || Boolean.TRUE.equals(resp.getTimedOut())
                || Boolean.TRUE.equals(resp.getVerificationDegraded()));
        int repairCount = resp != null && resp.getStages() != null
                ? (int) resp.getStages().stream()
                .filter(s -> s != null && "REPAIR".equals(s.getStage())).count()
                : 0;
        emitIfPresent(sink, ChatStreamEvent.builder()
                .type(ChatStreamEvent.TYPE_VERIFICATION)
                .verifyStatus(fail ? "FAILED" : "PASSED")
                .repairCount(repairCount)
                .traceId(traceId)
                .build());
    }

    /** delta 切片输出: 模型网关为同步返回, 后端对最终答案按码点切片模拟流式(默认 60 字符/10ms) */
    private void emitDelta(ChatStreamSink sink, ChatSendResult result) {
        if (sink == null || result == null || StrUtil.isBlank(result.getAnswer())) return;
        int chunkSize = Math.max(1, chatProperties.getStreamChunkSize());
        long delayMs = Math.max(0, chatProperties.getStreamChunkDelayMs());
        int[] codePoints = result.getAnswer().codePoints().toArray();
        for (int i = 0; i < codePoints.length; i += chunkSize) {
            if (sink.isCancelled()) break;
            int end = Math.min(codePoints.length, i + chunkSize);
            StringBuilder sb = new StringBuilder(end - i);
            for (int j = i; j < end; j++) {
                sb.appendCodePoint(codePoints[j]);
            }
            emitIfPresent(sink, ChatStreamEvent.builder()
                    .type(ChatStreamEvent.TYPE_DELTA)
                    .content(sb.toString())
                    .build());
            if (delayMs > 0 && end < codePoints.length) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void emitDone(ChatStreamSink sink, ChatSendResult result, String traceId) {
        if (sink == null || result == null) return;
        emitIfPresent(sink, ChatStreamEvent.builder()
                .type(ChatStreamEvent.TYPE_DONE)
                .conversationId(result.getConversationId())
                .queryId(traceId)
                .traceId(traceId)
                .messageId(result.getMessageId())
                .route(result.getRoute())
                .answerable(result.getAnswerable())
                .answer(result.getAnswer())
                .citations(result.getCitations())
                .evidence(result.getEvidence())
                .confidence(result.getConfidence())
                .latencyMs(result.getLatencyMs())
                .degraded(result.getDegraded())
                .transferRequired(result.getTransferRequired())
                .transferReason(result.getTransferReason())
                .build());
    }

    private void emitError(ChatStreamSink sink, String code, String message, Boolean retryable, String traceId) {
        if (sink == null) return;
        emitIfPresent(sink, ChatStreamEvent.builder()
                .type(ChatStreamEvent.TYPE_ERROR)
                .code(code)
                .message(message)
                .retryable(retryable)
                .traceId(traceId)
                .build());
    }

    private void emitIfPresent(ChatStreamSink sink, ChatStreamEvent event) {
        if (sink == null) return;
        try {
            sink.emit(event);
        } catch (Exception e) {
            log.warn("[emitIfPresent][事件输出失败, 按取消处理: {}]", e.getMessage());
        }
    }

    /** 会话/知识库类错误用户需修正后重试(不可自动重试); 其余(超时/模型/检索)可重试 */
    private boolean isRetryableError(Integer code) {
        if (code == null) return false;
        return switch (code) {
            case 1_003_000_002, 1_003_000_005, 1_003_000_007, 1_003_000_008 -> false; // 会话/知识库上下文错误
            default -> true;
        };
    }
}
