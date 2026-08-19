package cn.iocoder.yudao.module.eval.api;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.eval.enums.ApiConstants;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
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
     * @param kbId             知识库编号(可为空 = 全部用例池, 不限定知识库)
     * @param question         问题(来自反馈对应的用户消息)
     * @param sourceFeedbackId 来源反馈编号(ai_feedback.id)
     * @return 新用例编号
     */
    @PostMapping(ApiConstants.PREFIX + "/create-case-from-feedback")
    CommonResult<Long> createCaseFromFeedback(@RequestParam(value = "kbId", required = false) Long kbId,
                                              @RequestParam("question") String question,
                                              @RequestParam("sourceFeedbackId") Long sourceFeedbackId);

    /**
     * 上线闸门检查(knowledge 发布前调用): 该知识库是否允许发布
     * <p>
     * 语义: 闸门配置关闭(yudao.eval.gate.enabled=false) → 恒 true(不阻断, 测试环境友好);
     * 无 DONE 评测任务 → false(未评测, 阻断); 有任务但 gatePass=0(未全题达标) → false(阻断);
     * 最新 DONE 任务全题达标(gatePass=1) → true。实现侧不抛异常, RPC 级故障由调用方处理。
     *
     * @param kbId 知识库编号
     * @return true=放行 / false=阻断
     */
    @GetMapping(ApiConstants.PREFIX + "/check-gate")
    CommonResult<Boolean> checkGate(@RequestParam("kbId") Long kbId);

}
