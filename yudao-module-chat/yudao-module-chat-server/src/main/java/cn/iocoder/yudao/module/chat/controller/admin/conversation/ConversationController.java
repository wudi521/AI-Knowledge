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
import cn.iocoder.yudao.module.chat.dal.dataobject.message.AiMessageEvidenceDO;
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
import java.util.Map;
import java.util.stream.Collectors;

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
        List<AiMessageDO> messages = messageService.getMessages(conversationId);
        resp.setMessages(convertMessages(messages));
        return success(resp);
    }

    @GetMapping("/page")
    @Operation(summary = "会话分页(状态筛选, 按创建时间倒序; 全租户, 仅管理端 admin-query 权限)")
    @PreAuthorize("@ss.hasPermission('chat:conversation:admin-query')")
    public CommonResult<PageResult<AiConversationDO>> page(@Valid ConversationPageReqVO reqVO) {
        return success(conversationService.getConversationPage(reqVO));
    }

    @GetMapping("/my-page")
    @Operation(summary = "当前用户的会话分页(状态筛选, 按创建时间倒序; 用户范围隔离)")
    @PreAuthorize("@ss.hasPermission('chat:conversation:query')")
    public CommonResult<PageResult<AiConversationDO>> myPage(@Valid ConversationPageReqVO reqVO) {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        return success(conversationService.getMyConversationPage(reqVO, userId));
    }

    // ========== 工具 ==========

    /**
     * 消息 DO → VO: citations 为 JSON 数组字符串, 解析为 List(供前端直接消费), 解析失败/为空 → 空列表。
     * P0-08: 附带每条消息持久化的证据快照(历史会话 Evidence 不丢)。
     */
    private List<MessageVO> convertMessages(List<AiMessageDO> messages) {
        if (messages == null || messages.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> messageIds = messages.stream().map(AiMessageDO::getId).collect(Collectors.toList());
        Map<Long, List<AiMessageEvidenceDO>> evidenceMap =
                messageService.getEvidenceMapByMessageIds(messageIds);
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
            vo.setEvidence(toEvidenceVO(evidenceMap.getOrDefault(message.getId(), Collections.emptyList())));
            result.add(vo);
        }
        return result;
    }

    private List<MessageVO.EvidenceVO> toEvidenceVO(List<AiMessageEvidenceDO> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        List<MessageVO.EvidenceVO> list = new ArrayList<>(rows.size());
        for (AiMessageEvidenceDO row : rows) {
            MessageVO.EvidenceVO vo = new MessageVO.EvidenceVO();
            vo.setEvidenceIndex(row.getEvidenceIndex());
            vo.setCitationLabel(row.getCitationLabel());
            vo.setDocumentId(row.getDocumentId());
            vo.setVersionId(row.getVersionId());
            vo.setChunkId(row.getChunkId());
            vo.setKbId(row.getKbId());
            vo.setDomainCode(row.getDomainCode());
            vo.setSectionType(row.getSectionType());
            vo.setSectionTitle(row.getSectionTitle());
            vo.setClaimNo(row.getClaimNo());
            vo.setPageStart(row.getPageStart());
            vo.setPageEnd(row.getPageEnd());
            vo.setApplicationNo(row.getApplicationNo());
            vo.setPublicationNo(row.getPublicationNo());
            vo.setDocumentName(row.getDocumentName());
            vo.setVersionNo(row.getVersionNo());
            vo.setContentSnapshot(row.getContentSnapshot());
            vo.setScore(row.getScore());
            list.add(vo);
        }
        return list;
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
