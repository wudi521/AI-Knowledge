package cn.iocoder.yudao.module.chat.controller.admin.feedback;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.chat.controller.admin.feedback.vo.FeedbackRespVO;
import cn.iocoder.yudao.module.chat.controller.admin.feedback.vo.FeedbackStatsRespVO;
import cn.iocoder.yudao.module.chat.controller.admin.feedback.vo.FeedbackUpsertReqVO;
import cn.iocoder.yudao.module.chat.service.feedback.FeedbackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - AI 回答反馈")
@RestController
@RequestMapping("/chat/feedback")
@Validated
public class FeedbackController {

    @Resource
    private FeedbackService feedbackService;

    @PostMapping
    @Operation(summary = "提交/更新反馈(按 messageId Upsert; 点踩自动生成评测考题)")
    @PreAuthorize("@ss.hasPermission('chat:chat:send')")
    public CommonResult<Long> upsert(@Valid @RequestBody FeedbackUpsertReqVO req) {
        return success(feedbackService.upsert(req.getMessageId(), req.getRating(), req.getReasonCode(), req.getComment()));
    }

    @GetMapping("/{messageId}")
    @Operation(summary = "查询消息的当前反馈(前端恢复已反馈状态)")
    @PreAuthorize("@ss.hasPermission('chat:chat:send')")
    public CommonResult<FeedbackRespVO> getByMessageId(@PathVariable("messageId") Long messageId) {
        return success(feedbackService.getByMessageId(messageId));
    }

    @GetMapping("/stats")
    @Operation(summary = "反馈统计(总数/有用/无用/rate, P0)")
    @PreAuthorize("@ss.hasPermission('chat:chat:send')")
    public CommonResult<FeedbackStatsRespVO> stats() {
        return success(feedbackService.stats());
    }

}
