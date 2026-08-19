package cn.iocoder.yudao.module.chat.controller.admin.feedback;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.chat.controller.admin.feedback.vo.FeedbackCreateReqVO;
import cn.iocoder.yudao.module.chat.service.feedback.FeedbackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - AI 反馈")
@RestController
@RequestMapping("/chat/feedback")
@Validated
public class FeedbackController {

    @Resource
    private FeedbackService feedbackService;

    @PostMapping("/create")
    @Operation(summary = "创建反馈(点赞/点踩; 点踩自动生成评测考题)")
    @PreAuthorize("@ss.hasPermission('chat:chat:send')")
    public CommonResult<Long> createFeedback(@Valid @RequestBody FeedbackCreateReqVO req) {
        return success(feedbackService.createFeedback(req.getMessageId(), req.getType(), req.getNote()));
    }

}
