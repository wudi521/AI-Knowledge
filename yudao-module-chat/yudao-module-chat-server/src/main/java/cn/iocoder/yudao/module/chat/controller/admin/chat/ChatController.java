package cn.iocoder.yudao.module.chat.controller.admin.chat;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.chat.controller.admin.chat.vo.ChatSendReqVO;
import cn.iocoder.yudao.module.chat.controller.admin.chat.vo.ChatSendRespVO;
import cn.iocoder.yudao.module.chat.controller.admin.chat.vo.ChatStreamEvent;
import cn.iocoder.yudao.module.chat.framework.chat.ChatProperties;
import cn.iocoder.yudao.module.chat.framework.chat.ChatStreamExecutor;
import cn.iocoder.yudao.module.chat.framework.chat.DeferredDoneChatStreamSink;
import cn.iocoder.yudao.module.chat.framework.chat.SseChatStreamSink;
import cn.iocoder.yudao.module.chat.service.chat.ChatPipeline;
import cn.iocoder.yudao.module.chat.service.chat.ChatSendResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.module.chat.enums.ErrorCodeConstants.CHAT_STREAM_IN_FLIGHT;

@Tag(name = "管理后台 - AI 对话")
@RestController
@RequestMapping("/chat/chat")
@Validated
public class ChatController {

    @Resource
    private ChatPipeline chatPipeline;
    @Resource
    private ChatStreamExecutor chatStreamExecutor;
    @Resource
    private ChatProperties chatProperties;

    /** 幂等 guard: 同一用户同时只允许一个流式问答(前端双击/并发防重复 Query) */
    private final ConcurrentHashMap<Long, AtomicBoolean> streamInflight = new ConcurrentHashMap<>();

    @PostMapping("/send")
    @Operation(summary = "发送消息(复用证据平台判定: 可作答带引用回答/不可作答自动转人工)")
    @PreAuthorize("@ss.hasPermission('chat:chat:send')")
    public CommonResult<ChatSendRespVO> send(@Valid @RequestBody ChatSendReqVO req) {
        ChatSendResult result = chatPipeline.send(req.getConversationId(), req.getMessage(),
                req.getChannel(), req.getCustomerId(), req.getKbId());
        return success(BeanUtils.toBean(result, ChatSendRespVO.class));
    }

    /**
     * SSE 流式发送。SSE-08: 禁用普通业务 ApiAccessLog——MVC afterCompletion 只能体现 HTTP
     * handshake 耗时(约 0ms), 无法表达真实 SSE 生命周期; 真实生命周期由 Query Trace 记录。
     */
    @ApiAccessLog(enable = false)
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式发送消息(SSE: conversation/stage/evidence/delta/verification/done/error)")
    @PreAuthorize("@ss.hasPermission('chat:chat:send')")
    public SseEmitter stream(@Valid @RequestBody ChatSendReqVO req) {
        LoginUser loginUser = SecurityFrameworkUtils.getLoginUser();
        Long userId = loginUser != null ? loginUser.getId() : null;
        // 幂等: 同一用户已有流式问答进行中 → 直接返回 error 事件(不启动第二个 Query)
        if (userId != null && streamInflight.putIfAbsent(userId, new AtomicBoolean(true)) != null) {
            SseEmitter reject = new SseEmitter(0L);
            new SseChatStreamSink(reject).emitErrorAndComplete(ChatStreamEvent.builder()
                    .type(ChatStreamEvent.TYPE_ERROR)
                    .code(String.valueOf(CHAT_STREAM_IN_FLIGHT.getCode()))
                    .message(CHAT_STREAM_IN_FLIGHT.getMsg())
                    .retryable(true)
                    .build());
            return reject;
        }
        SseEmitter emitter = new SseEmitter(chatProperties.getStreamTimeoutMs());
        SseChatStreamSink transportSink = new SseChatStreamSink(emitter);
        // P0 commit-before-done: Pipeline 内部先产生 done，但先缓存；只有整个 Pipeline 返回
        // (AI 消息/ResultSet/ContextFrame/Trace 关键状态均已处理完) 后才真正发送给前端。
        DeferredDoneChatStreamSink sink = new DeferredDoneChatStreamSink(transportSink);
        Long tenantId = loginUser != null ? loginUser.getTenantId() : null;
        chatStreamExecutor.execute(() -> {
            boolean pipelineCompleted = false;
            try {
                // 恢复租户上下文(DB 落库 tenant_id 自动填充) + 安全上下文(Pipeline 读取登录用户)
                TenantUtils.execute(tenantId, () -> {
                    restoreSecurityContext(loginUser);
                    try {
                        chatPipeline.stream(req.getConversationId(), req.getMessage(),
                                req.getChannel(), req.getCustomerId(), req.getKbId(), sink);
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                });
                pipelineCompleted = true;
                // 只有正常返回才 flush done；如果客户端已取消，Deferred Sink 会自动丢弃。
                sink.flushDone();
            } catch (Exception e) {
                // 异常路径不允许 done/error 双终态。
                sink.discardDone();
                transportSink.emitErrorAndComplete(ChatStreamEvent.builder()
                        .type(ChatStreamEvent.TYPE_ERROR)
                        .code("INTERNAL")
                        .message("系统繁忙，请稍后重试")
                        .retryable(false)
                        .build());
            } finally {
                if (!pipelineCompleted) {
                    sink.discardDone();
                }
                if (userId != null) {
                    streamInflight.remove(userId);
                }
                // transport complete 幂等：已取消/超时/error/complete 均 no-op。
                transportSink.complete();
            }
        });
        return emitter;
    }

    /** 在流式执行线程恢复登录用户上下文(Pipeline 内 {@code SecurityFrameworkUtils.getLoginUser()} 依赖) */
    private void restoreSecurityContext(LoginUser loginUser) {
        if (loginUser == null) {
            return;
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(loginUser, null, Collections.emptyList()));
        SecurityContextHolder.setContext(context);
    }

}
