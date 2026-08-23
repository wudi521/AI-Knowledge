package cn.iocoder.yudao.module.chat.controller.admin.conversation;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.chat.controller.admin.conversation.vo.ConversationHistoryRespVO;
import cn.iocoder.yudao.module.chat.controller.admin.conversation.vo.ConversationInfoVO;
import cn.iocoder.yudao.module.chat.controller.admin.conversation.vo.ConversationPageReqVO;
import cn.iocoder.yudao.module.chat.controller.admin.conversation.vo.ConversationTakeOverReqVO;
import cn.iocoder.yudao.module.chat.controller.admin.conversation.vo.ConversationTransferReqVO;
import cn.iocoder.yudao.module.chat.controller.admin.conversation.vo.MessageVO;
import cn.iocoder.yudao.module.chat.dal.dataobject.conversation.AiConversationDO;
import cn.iocoder.yudao.module.chat.dal.dataobject.message.AiMessageDO;
import cn.iocoder.yudao.module.chat.service.conversation.ConversationService;
import cn.iocoder.yudao.module.chat.service.message.MessageService;
import cn.iocoder.yudao.module.chat.service.transfer.TransferHandler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.module.chat.enums.ErrorCodeConstants.CONVERSATION_NOT_EXISTS;

@Tag(name = "管理后台 - AI 会话")
@Slf4j
@RestController
@RequestMapping("/chat/conversation")
@Validated
public class ConversationController {

    @Resource
    private ConversationService conversationService;
    @Resource
    private MessageService messageService;
    @Resource
    private TransferHandler transferHandler;

    @PostMapping("/transfer")
    @Operation(summary = "会话手动转人工(坐席触发)")
    @PreAuthorize("@ss.hasPermission('chat:conversation:transfer')")
    public CommonResult<String> transfer(@Valid @RequestBody ConversationTransferReqVO req) {
        return success(transferHandler.manualTransfer(req.getConversationId(), req.getReason()));
    }

    @PostMapping("/take-over")
    @Operation(summary = "坐席接管会话")
    @PreAuthorize("@ss.hasPermission('chat:conversation:take-over')")
    public CommonResult<Boolean> takeOver(@Valid @RequestBody ConversationTakeOverReqVO req) {
        transferHandler.takeOver(req.getConversationId());
        return success(true);
    }

    @GetMapping("/history")
    @Operation(summary = "获取会话历史(会话信息 + 消息列表)")
    @PreAuthorize("@ss.hasPermission('chat:conversation:query')")
    public CommonResult<ConversationHistoryRespVO> history(@RequestParam("conversationId") Long conversationId) {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        AiConversationDO conversation = conversationService.getConversationForUser(conversationId, userId);
        if (conversation == null) {
            throw new ServiceException(CONVERSATION_NOT_EXISTS);
        }
        ConversationHistoryRespVO resp = new ConversationHistoryRespVO();
        resp.setConversation(BeanUtils.toBean(conversation, ConversationInfoVO.class));
        resp.setMessages(convertMessages(messageService.getMessages(conversationId)));
        return success(resp);
    }

    @GetMapping("/page")
    @Operation(summary = "会话分页(状态筛选, 按创建时间倒序)")
    @PreAuthorize("@ss.hasPermission('chat:conversation:query')")
    public CommonResult<PageResult<AiConversationDO>> page(@Valid ConversationPageReqVO reqVO) {
        return success(conversationService.getConversationPage(reqVO));
    }

    // ========== 工具 ==========

    /**
     * 消息 DO → VO: citations 为 JSON 数组字符串, 解析为 List(供前端直接消费), 解析失败/为空 → 空列表
     */
    private List<MessageVO> convertMessages(List<AiMessageDO> messages) {
        if (messages == null || messages.isEmpty()) {
            return Collections.emptyList();
        }
        List<MessageVO> result = new ArrayList<>(messages.size());
        for (AiMessageDO message : messages) {
            MessageVO vo = new MessageVO();
            vo.setId(message.getId());
            vo.setRole(message.getRole());
            vo.setContent(message.getContent());
            vo.setCitations(parseCitations(message.getCitations()));
            vo.setIntent(message.getIntent());
            vo.setConfidence(message.getConfidence());
            vo.setTraceId(message.getTraceId());
            vo.setCreateTime(message.getCreateTime());
            result.add(vo);
        }
        return result;
    }

    private List<String> parseCitations(String citationsJson) {
        if (StrUtil.isBlank(citationsJson)) {
            return Collections.emptyList();
        }
        try {
            return JSONUtil.toList(citationsJson, String.class);
        } catch (Exception e) {
            log.warn("[parseCitations][解析引用证据失败, 返回空列表: {}]", citationsJson);
            return Collections.emptyList();
        }
    }

}
