package cn.iocoder.yudao.module.chat.service.feedback;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.chat.dal.dataobject.feedback.AiFeedbackDO;
import cn.iocoder.yudao.module.chat.dal.dataobject.message.AiMessageDO;
import cn.iocoder.yudao.module.chat.dal.mysql.feedback.AiFeedbackMapper;
import cn.iocoder.yudao.module.chat.dal.mysql.message.AiMessageMapper;
import cn.iocoder.yudao.module.chat.enums.feedback.FeedbackTypeEnum;
import cn.iocoder.yudao.module.eval.api.EvalApi;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static cn.iocoder.yudao.module.chat.enums.ErrorCodeConstants.FEEDBACK_TYPE_ERROR;
import static cn.iocoder.yudao.module.chat.enums.ErrorCodeConstants.MESSAGE_NOT_EXISTS;

/**
 * AI 反馈 Service: 点赞/点踩落库 + 点踩→考题闭环(异步调 eval 生成评测用例, 不阻塞反馈响应)
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
    private AiFeedbackMapper aiFeedbackMapper;
    @Resource
    private AiMessageMapper aiMessageMapper;
    @Resource
    private EvalApi evalApi;

    /**
     * 创建反馈(点赞/点踩)
     * <p>
     * 点踩(THUMB_DOWN)落库后异步调 evalApi.createCaseFromFeedback 生成评测用例
     * (kbId=null 全部用例池, question=被反馈消息内容, sourceFeedbackId=本反馈编号),
     * 成功后回填 ai_feedback.eval_case_id; 异步失败仅记录日志, 不抛出、不影响反馈响应。
     *
     * @return 反馈编号
     */
    public Long createFeedback(Long messageId, String type, String note) {
        if (!FeedbackTypeEnum.isValid(type)) {
            throw new ServiceException(FEEDBACK_TYPE_ERROR);
        }
        AiMessageDO message = aiMessageMapper.selectById(messageId);
        if (message == null) {
            throw new ServiceException(MESSAGE_NOT_EXISTS);
        }
        AiFeedbackDO feedback = AiFeedbackDO.builder()
                .messageId(messageId)
                .type(type)
                .note(StrUtil.maxLength(note, 512))
                .build();
        aiFeedbackMapper.insert(feedback);
        log.info("[createFeedback][反馈 {} 落库: messageId={}, type={}]", feedback.getId(), messageId, type);
        if (FeedbackTypeEnum.THUMB_DOWN.getType().equals(type)) {
            generateCaseAsync(feedback.getId(), message.getContent());
        }
        return feedback.getId();
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
            AiFeedbackDO update = new AiFeedbackDO();
            update.setId(feedbackId);
            update.setEvalCaseId(result.getData());
            aiFeedbackMapper.updateById(update);
            log.info("[doGenerateCase][反馈 {} 生成考题 {} 完成]", feedbackId, result.getData());
        } catch (Exception e) {
            log.error("[doGenerateCase][反馈 {} 生成考题 RPC 异常]", feedbackId, e);
        }
    }

}
