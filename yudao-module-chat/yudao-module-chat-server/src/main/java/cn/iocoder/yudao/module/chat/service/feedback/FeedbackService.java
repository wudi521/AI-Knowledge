package cn.iocoder.yudao.module.chat.service.feedback;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.chat.controller.admin.feedback.vo.FeedbackRespVO;
import cn.iocoder.yudao.module.chat.controller.admin.feedback.vo.FeedbackStatsRespVO;
import cn.iocoder.yudao.module.chat.dal.dataobject.feedback.AiChatFeedbackDO;
import cn.iocoder.yudao.module.chat.dal.dataobject.message.AiMessageDO;
import cn.iocoder.yudao.module.chat.dal.dataobject.message.AiMessageEvidenceDO;
import cn.iocoder.yudao.module.chat.dal.dataobject.trace.AiQueryTraceDO;
import cn.iocoder.yudao.module.chat.dal.mysql.feedback.AiChatFeedbackMapper;
import cn.iocoder.yudao.module.chat.dal.mysql.message.AiMessageMapper;
import cn.iocoder.yudao.module.chat.enums.feedback.FeedbackRatingEnum;
import cn.iocoder.yudao.module.chat.enums.feedback.FeedbackReasonEnum;
import cn.iocoder.yudao.module.chat.service.conversation.ConversationService;
import cn.iocoder.yudao.module.chat.service.message.MessageService;
import cn.iocoder.yudao.module.chat.service.trace.QueryTraceService;
import cn.iocoder.yudao.module.eval.api.EvalApi;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static cn.iocoder.yudao.module.chat.enums.ErrorCodeConstants.FEEDBACK_NOT_ALLOWED;
import static cn.iocoder.yudao.module.chat.enums.ErrorCodeConstants.FEEDBACK_RATING_INVALID;
import static cn.iocoder.yudao.module.chat.enums.ErrorCodeConstants.FEEDBACK_REASON_INVALID;
import static cn.iocoder.yudao.module.chat.enums.ErrorCodeConstants.MESSAGE_NOT_EXISTS;

/**
 * AI 回答反馈 Service(P0-11): 有用/无用 Upsert + 自动关联 Query/Trace/Route/Evidence + 点踩→考题闭环。
 * <p>
 * 只记录质量信号, 禁止把一次 👍/👎 直接作为训练标签喂给模型; 点踩落库后异步生成评测用例
 * (kbId=null 全部用例池, question=被反馈消息内容, sourceFeedbackId=本反馈编号), 成功回填 eval_case_id。
 */
@Slf4j
@Service
public class FeedbackService {

    /** 点踩转考题异步线程池(daemon 线程, 应用退出不阻塞) */
    private static final ExecutorService EVAL_ASYNC_EXECUTOR = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "eval-case-from-feedback");
        t.setDaemon(true);
        return t;
    });

    @Resource
    private AiChatFeedbackMapper aiChatFeedbackMapper;
    @Resource
    private AiMessageMapper aiMessageMapper;
    @Resource
    private MessageService messageService;
    @Resource
    private ConversationService conversationService;
    @Resource
    private QueryTraceService queryTraceService;
    @Resource
    private EvalApi evalApi;

    /**
     * Upsert 反馈(按 messageId 唯一): 校验评价/原因合法、消息存在且属于当前用户, 自动补齐
     * conversationId/queryTraceId/traceId/tenantId/userId/kbId/domainCode/route/intent/confidence/
     * latencyMs/evidence(客户端不信任字段一律服务端补齐)。NOT_HELPFUL 触发异步考题闭环。
     *
     * @return 反馈编号(新增或已存在)
     */
    public Long upsert(Long messageId, String rating, String reasonCode, String comment) {
        if (!FeedbackRatingEnum.isValid(rating)) {
            throw new ServiceException(FEEDBACK_RATING_INVALID);
        }
        if (FeedbackRatingEnum.NOT_HELPFUL.getRating().equals(rating) && StrUtil.isBlank(reasonCode)) {
            // 点踩必选原因(供 Failure Classification / Bad Case 统计)
            throw new ServiceException(FEEDBACK_REASON_INVALID);
        }
        if (StrUtil.isNotBlank(reasonCode) && !FeedbackReasonEnum.isValid(reasonCode)) {
            throw new ServiceException(FEEDBACK_REASON_INVALID);
        }
        AiMessageDO message = aiMessageMapper.selectById(messageId);
        if (message == null) {
            throw new ServiceException(MESSAGE_NOT_EXISTS);
        }
        if (!"AI".equals(message.getRole())) {
            // 只允许对 AI 回答反馈
            throw new ServiceException(FEEDBACK_NOT_ALLOWED);
        }
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        // owner 校验: 被反馈消息所属会话必须属于当前用户(非 Message Owner 不能提交)
        if (userId != null && conversationService.getConversationForUser(message.getConversationId(), userId) == null) {
            throw new ServiceException(FEEDBACK_NOT_ALLOWED);
        }
        AiChatFeedbackDO existing = aiChatFeedbackMapper.selectByMessageId(messageId);
        AiChatFeedbackDO feedback;
        if (existing != null) {
            feedback = existing;
            feedback.setRating(rating);
            feedback.setReasonCode(StrUtil.maxLength(reasonCode, 32));
            feedback.setComment(StrUtil.maxLength(comment, 1000));
            aiChatFeedbackMapper.updateById(feedback);
        } else {
            feedback = buildFeedback(message, rating, reasonCode, comment);
            feedback.setUserId(userId);
            aiChatFeedbackMapper.insert(feedback);
        }
        log.info("[upsert][反馈 {} 落库: messageId={}, rating={}, reason={}]",
                feedback.getId(), messageId, rating, reasonCode);
        if (FeedbackRatingEnum.NOT_HELPFUL.getRating().equals(rating)) {
            generateCaseAsync(feedback.getId(), message.getContent());
        }
        return feedback.getId();
    }

    /** 查询单条消息的当前反馈(前端恢复已反馈状态) */
    public FeedbackRespVO getByMessageId(Long messageId) {
        AiChatFeedbackDO feedback = aiChatFeedbackMapper.selectByMessageId(messageId);
        return feedback == null ? null : BeanUtils.toBean(feedback, FeedbackRespVO.class);
    }

    /** 基础统计(当前租户): 总数/有用/无用/rate(P0 只计数) */
    public FeedbackStatsRespVO stats() {
        Long total = aiChatFeedbackMapper.selectCountAll();
        Long helpful = aiChatFeedbackMapper.selectCount(new LambdaQueryWrapperX<AiChatFeedbackDO>()
                .eq(AiChatFeedbackDO::getRating, FeedbackRatingEnum.HELPFUL.getRating()));
        Long notHelpful = aiChatFeedbackMapper.selectCount(new LambdaQueryWrapperX<AiChatFeedbackDO>()
                .eq(AiChatFeedbackDO::getRating, FeedbackRatingEnum.NOT_HELPFUL.getRating()));
        return FeedbackStatsRespVO.builder()
                .totalCount(total)
                .helpfulCount(helpful)
                .notHelpfulCount(notHelpful)
                .helpfulRate(total == null || total == 0 ? null : (double) helpful / total)
                .notHelpfulRate(total == null || total == 0 ? null : (double) notHelpful / total)
                .build();
    }

    /** 构建反馈(自动关联 message→Query Trace→Evidence 上下文, 不信任客户端) */
    private AiChatFeedbackDO buildFeedback(AiMessageDO message, String rating, String reasonCode, String comment) {
        AiChatFeedbackDO feedback = AiChatFeedbackDO.builder()
                .messageId(message.getId())
                .conversationId(message.getConversationId())
                .queryTraceId(message.getQueryTraceId())
                .traceId(message.getTraceId())
                .route(message.getRoute())
                .intent(message.getIntent())
                .confidence(message.getConfidence())
                .rating(rating)
                .reasonCode(StrUtil.maxLength(reasonCode, 32))
                .comment(StrUtil.maxLength(comment, 1000))
                .build();
        // Query Trace 关联: kbId/domainCode/latency(route 缺省时兜底)
        if (StrUtil.isNotBlank(message.getQueryTraceId())) {
            AiQueryTraceDO trace = queryTraceService.getTrace(message.getQueryTraceId());
            if (trace != null) {
                feedback.setKbId(trace.getKbId());
                feedback.setDomainCode(trace.getDomainCode());
                feedback.setLatencyMs(trace.getTotalMs());
                if (StrUtil.isBlank(feedback.getRoute())) {
                    feedback.setRoute(trace.getRoute());
                }
            }
        }
        // Evidence 关联: 主文档 + 快照 JSON(Bad Case 复现)
        List<AiMessageEvidenceDO> evidence = messageService.getEvidenceByMessageId(message.getId());
        if (CollUtil.isNotEmpty(evidence)) {
            feedback.setPrimaryDocumentId(evidence.get(0).getDocumentId());
            if (feedback.getKbId() == null) {
                feedback.setKbId(evidence.get(0).getKbId());
            }
            if (StrUtil.isBlank(feedback.getDomainCode())) {
                feedback.setDomainCode(evidence.get(0).getDomainCode());
            }
            feedback.setEvidenceSnapshot(JSONUtil.toJsonStr(evidence.stream().map(e -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("evidenceId", e.getChunkId());
                m.put("chunkId", e.getChunkId());
                m.put("documentId", e.getDocumentId());
                m.put("documentName", e.getDocumentName());
                m.put("versionId", e.getVersionId());
                m.put("versionNo", e.getVersionNo());
                m.put("sectionType", e.getSectionType());
                m.put("sectionTitle", e.getSectionTitle());
                m.put("claimNo", e.getClaimNo());
                m.put("pageStart", e.getPageStart());
                m.put("pageEnd", e.getPageEnd());
                m.put("applicationNo", e.getApplicationNo());
                m.put("publicationNo", e.getPublicationNo());
                m.put("content", StrUtil.sub(e.getContentSnapshot(), 0, 500));
                return m;
            }).toList()));
        }
        return feedback;
    }

    /**
     * 异步生成考题: 捕获请求线程租户, 异步线程内恢复租户上下文(DB 落库 + Feign tenant header 均需要)
     */
    private void generateCaseAsync(Long feedbackId, String question) {
        Long tenantId = TenantContextHolder.getTenantId();
        EVAL_ASYNC_EXECUTOR.execute(() -> {
            try {
                TenantUtils.execute(tenantId, () -> doGenerateCase(feedbackId, question));
            } catch (Exception e) {
                // 异步兜底: 只记录, 不抛出(反馈链路已成功, 考题可人工补建)
                log.error("[generateCaseAsync][反馈 {} 生成考题异步执行异常]", feedbackId, e);
            }
        });
    }

    private void doGenerateCase(Long feedbackId, String question) {
        try {
            CommonResult<Long> result = evalApi.createCaseFromFeedback(null, question, feedbackId);
            if (result.isError()) {
                log.warn("[doGenerateCase][反馈 {} 生成考题失败: code={}, msg={}]",
                        feedbackId, result.getCode(), result.getMsg());
                return;
            }
            // 回填 eval_case_id(闭环可追溯)
            AiChatFeedbackDO update = new AiChatFeedbackDO();
            update.setId(feedbackId);
            update.setEvalCaseId(result.getData());
            aiChatFeedbackMapper.updateById(update);
            log.info("[doGenerateCase][反馈 {} 生成考题 {} 完成]", feedbackId, result.getData());
        } catch (Exception e) {
            log.error("[doGenerateCase][反馈 {} 生成考题 RPC 异常]", feedbackId, e);
        }
    }

}
