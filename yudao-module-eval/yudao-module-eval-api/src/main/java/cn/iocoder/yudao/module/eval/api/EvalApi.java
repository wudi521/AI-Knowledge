package cn.iocoder.yudao.module.eval.api;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.eval.enums.ApiConstants;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 评测平台 对外 RPC 接口(Feign)
 * 其他模块通过 Feign 调用本接口, 实现位于 eval-server
 */
@FeignClient(name = ApiConstants.NAME)
public interface EvalApi {

    /**
     * 反馈转评测用例(chat 模块在反馈落库后调用; 标准答案/标准证据待人工补充, 分类默认"综合")
     *
     * @param kbId             知识库编号
     * @param question         问题(来自反馈对应的用户消息)
     * @param sourceFeedbackId 来源反馈编号(ai_feedback.id)
     * @return 新用例编号
     */
    @PostMapping(ApiConstants.PREFIX + "/create-case-from-feedback")
    CommonResult<Long> createCaseFromFeedback(@RequestParam("kbId") Long kbId,
                                              @RequestParam("question") String question,
                                              @RequestParam("sourceFeedbackId") Long sourceFeedbackId);

}
