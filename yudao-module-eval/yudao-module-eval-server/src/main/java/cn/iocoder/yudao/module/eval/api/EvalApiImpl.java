package cn.iocoder.yudao.module.eval.api;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.eval.service.cases.EvalCaseService;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 评测平台 对外 RPC 实现
 */
@RestController // 提供 RESTful API 接口，给 Feign 调用
@Validated
public class EvalApiImpl implements EvalApi {

    @Resource
    private EvalCaseService evalCaseService;

    @Override
    public CommonResult<Long> createCaseFromFeedback(Long kbId, String question, Long sourceFeedbackId) {
        return success(evalCaseService.createCaseFromFeedback(kbId, question, sourceFeedbackId));
    }

}
