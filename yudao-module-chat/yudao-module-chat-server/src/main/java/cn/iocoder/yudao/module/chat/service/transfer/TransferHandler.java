package cn.iocoder.yudao.module.chat.service.transfer;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.chat.framework.chat.TransferProperties;
import cn.iocoder.yudao.module.chat.service.chat.ChatSendResult;
import cn.iocoder.yudao.module.chat.service.conversation.ConversationService;
import cn.iocoder.yudao.module.chat.service.message.MessageService;
import cn.iocoder.yudao.module.chat.dal.dataobject.message.AiMessageDO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 转人工处理器: 转人工触发判定 + 交接摘要 + 状态迁移 + SYSTEM 留痕
 * <p>
 * 职责:
 * <ul>
 *     <li><b>结构化触发判定</b>: 关键词(配置驱动, 非 LLM)命中 → 转人工原因("情绪激烈"优先于"客户要求");</li>
 *     <li><b>交接摘要</b>: {@link #buildSummary} 组装 "客户问题 | 原因 | AI建议(前100字) | 相关证据(前3条)" 摘要;</li>
 *     <li><b>转人工落地</b>: {@link #handleTransfer} 落库 SYSTEM 交接摘要消息 + 状态迁移 ACTIVE→TRANSFERRED
 *     (并发已 TRANSFERRED 时守卫返回 0 行, 仅告警不报错);</li>
 *     <li><b>上下文摘要</b>: {@link #buildRecentContext} 规则组装最近 3 条 USER/AI 消息为 "近轮对话要点"
 *     (非 LLM), 转人工/手动转人工时写入 ai_conversation.context_summary, 与交接摘要(summary)互补;</li>
 *     <li><b>手动转人工/接单</b>: {@link #manualTransfer} / {@link #takeOver} 供 Task 5 控制器接线,
 *     同样完成状态迁移 + SYSTEM 留痕。</li>
 * </ul>
 * 约束: 除会话不存在({@link ConversationService} 内部守卫抛 CONVERSATION_NOT_EXISTS)外, 本类方法永不抛出;
 * 会话状态并发变更导致的迁移失败一律降级为告警日志。依赖注入无环: TransferHandler → ConversationService/MessageService,
 * ChatPipeline → TransferHandler。
 */
@Slf4j
@Component
public class TransferHandler {

    /** 转人工原因: 情绪激烈(emotion-keywords 命中) */
    public static final String REASON_EMOTION = "情绪激烈";
    /** 转人工原因: 客户要求(transfer keywords 命中) */
    public static final String REASON_CUSTOMER_REQUEST = "客户要求";
    /** 转人工原因: 会话已关闭(终态会话收到新消息) */
    public static final String REASON_CLOSED = "会话已关闭";
    /** 转人工原因: 手动转人工时 reason 为空兜底 */
    private static final String REASON_MANUAL_DEFAULT = "人工转接";

    /** AI 建议摘要最大长度(前 100 字, 中文安全截断) */
    private static final int AI_SUGGESTION_MAX_LENGTH = 100;
    /** 摘要中相关证据最多条数(前 3 条文档名) */
    private static final int EVIDENCE_MAX_COUNT = 3;
    /** 近轮对话要点最多轮数(最近 3 条 USER/AI 消息, 排除 SYSTEM 交接) */
    private static final int RECENT_CONTEXT_MAX_TURNS = 3;
    /** 近轮对话要点中单条消息内容最大长度(前 60 字) */
    private static final int RECENT_CONTEXT_CONTENT_MAX_LENGTH = 60;

    @Resource
    private ConversationService conversationService;
    @Resource
    private MessageService messageService;
    @Resource
    private TransferProperties transferProperties;

    /**
     * 转人工落地: 落库 SYSTEM 交接摘要消息 + 会话状态迁移 ACTIVE→TRANSFERRED
     * <p>
     * 供 {@code ChatPipeline} 转人工路径调用 —— decision 已携带 pipeline 推导的 transferReason/summary,
     * 本方法仅做"留痕 + 状态迁移", 返回 decision 原样(transferRequired=true 等)。
     *
     * @param conversationId 会话编号
     * @param message        客户消息(仅用于告警日志)
     * @param decision       管线产出的转人工决策(含 transferReason/summary/traceId)
     * @return 原 decision 不变
     */
    public ChatSendResult handleTransfer(Long conversationId, String message, ChatSendResult decision) {
        if (decision == null) {
            log.warn("[handleTransfer][会话({}) 转人工决策为空, 忽略, message({})]", conversationId, message);
            return null;
        }
        // 1. 落库 SYSTEM 交接摘要消息(留痕; citations 置空)
        messageService.addMessage(conversationId, "SYSTEM", decision.getSummary(),
                null, null, null, null, decision.getTraceId());
        // 2. 状态迁移 + 摘要/原因(ACTIVE→TRANSFERRED; 并发已 TRANSFERRED 时守卫返回 0 行, 内部已 warn)
        conversationService.updateTransferInfo(conversationId, decision.getSummary(), decision.getTransferReason());
        // 3. 写入会话上下文摘要(近轮对话要点, 规则组装非 LLM; 与 decision.summary 的交接摘要互补)
        conversationService.updateContextSummary(conversationId, buildRecentContext(conversationId));
        return decision;
    }

    /**
     * 结构化转人工触发判定(配置关键词, 非 LLM)
     * <p>
     * 优先级: 情绪关键词(emotion-keywords, 更紧急) → 转人工关键词(keywords) → 无触发(null)。
     * 空白消息不触发。
     *
     * @param message 客户消息
     * @return 转人工原因; 无触发时返回 null
     */
    public String detectTransferReason(String message) {
        if (StrUtil.isBlank(message)) {
            return null;
        }
        if (containsAny(message, transferProperties.getEmotionKeywords())) {
            return REASON_EMOTION;
        }
        if (containsAny(message, transferProperties.getKeywords())) {
            return REASON_CUSTOMER_REQUEST;
        }
        return null;
    }

    /**
     * 交接摘要: "客户问题: {query} | 原因: {reason}" + (AI 建议非空) " | AI建议: {前 100 字}"
     * + (证据名非空) " | 相关证据: {前 3 条文档名, 逗号分隔}"
     * <p>
     * 中文安全截断(按码点, 不切断代理对); 缺失片段整体省略, 不输出空占位。
     *
     * @param query         客户问题(必填)
     * @param reason        转人工原因(必填)
     * @param aiSuggestion  AI 建议(可空, 取前 100 字)
     * @param evidenceNames 相关证据文档名列表(可空, 取前 3 条逗号分隔)
     * @return 交接摘要字符串
     */
    public String buildSummary(String query, String reason, String aiSuggestion, List<String> evidenceNames) {
        StringBuilder summary = new StringBuilder("客户问题: ").append(StrUtil.nullToEmpty(query))
                .append(" | 原因: ").append(StrUtil.nullToEmpty(reason));
        if (StrUtil.isNotBlank(aiSuggestion)) {
            summary.append(" | AI建议: ").append(StrUtil.maxLength(aiSuggestion.trim(), AI_SUGGESTION_MAX_LENGTH));
        }
        if (CollUtil.isNotEmpty(evidenceNames)) {
            List<String> topNames = evidenceNames.stream()
                    .filter(StrUtil::isNotBlank)
                    .limit(EVIDENCE_MAX_COUNT)
                    .toList();
            if (CollUtil.isNotEmpty(topNames)) {
                summary.append(" | 相关证据: ").append(String.join(", ", topNames));
            }
        }
        return summary.toString();
    }

    /**
     * 坐席手动转人工(控制器 Task 5 接线): 更新会话状态 + 落库 SYSTEM 留痕
     * <p>
     * 摘要固定为 "坐席手动转人工: {reason}", SYSTEM 消息为 "会话已转人工: {reason}";
     * reason 为空时兜底 "人工转接"。会话不存在抛 CONVERSATION_NOT_EXISTS(由
     * {@link ConversationService#updateTransferInfo} 内部守卫), 其余情况永不抛出。
     *
     * @param conversationId 会话编号
     * @param reason         转人工原因(可空)
     * @return 落库的交接摘要
     */
    public String manualTransfer(Long conversationId, String reason) {
        String transferReason = StrUtil.blankToDefault(reason, REASON_MANUAL_DEFAULT);
        String summary = "坐席手动转人工: " + transferReason;
        // 状态迁移 + 摘要/原因(ACTIVE→TRANSFERRED; 非 ACTIVE 时守卫失败仅 warn)
        conversationService.updateTransferInfo(conversationId, summary, transferReason);
        // 写入会话上下文摘要(近轮对话要点, 规则组装非 LLM)
        conversationService.updateContextSummary(conversationId, buildRecentContext(conversationId));
        // SYSTEM 留痕
        messageService.addMessage(conversationId, "SYSTEM", "会话已转人工: " + transferReason,
                null, null, null, null, null);
        return summary;
    }

    /**
     * 坐席接单(控制器 Task 5 接线): 状态迁移 TRANSFERRED→CLOSED + 记录接单客服 + SYSTEM 留痕
     * <p>
     * 接单客服取当前登录用户 {@link SecurityFrameworkUtils#getLoginUser()} 的 id(未登录时为 null,
     * 由 ConversationService 原样记录)。会话不存在抛 CONVERSATION_NOT_EXISTS, 其余情况永不抛出。
     *
     * @param conversationId 会话编号
     */
    public void takeOver(Long conversationId) {
        LoginUser loginUser = SecurityFrameworkUtils.getLoginUser();
        Long operatorId = loginUser != null ? loginUser.getId() : null;
        // 状态迁移 TRANSFERRED→CLOSED + 接单客服(非 TRANSFERRED 时守卫失败仅 warn)
        conversationService.takeOver(conversationId, operatorId);
        // SYSTEM 留痕
        messageService.addMessage(conversationId, "SYSTEM", "坐席已接管会话",
                null, null, null, null, null);
    }

    // ========== 工具 ==========

    /**
     * 近轮对话要点(规则组装, 非 LLM): 读取会话最近 3 条 USER/AI 消息(排除 SYSTEM 交接),
     * 按聊天顺序格式化为 "近轮对话要点: [用户] X / [客服] Y / [用户] Z", 供坐席在无 LLM 成本下
     * 快速掌握转人工前的对话上下文。
     * <p>
     * 角色映射: USER → 用户, AI → 客服(其余角色兜底原样输出); 每条内容换行折叠为空格后取前 60 字
     * (中文安全截断, 与 {@link #buildSummary} 一致); 无消息返回空串(不输出空占位)。
     *
     * @param conversationId 会话编号
     * @return 近轮对话要点; 无消息/会话不存在时返回空串
     */
    public String buildRecentContext(Long conversationId) {
        List<AiMessageDO> recent = messageService.getRecentMessages(conversationId, RECENT_CONTEXT_MAX_TURNS);
        if (CollUtil.isEmpty(recent)) {
            return "";
        }
        StringBuilder context = new StringBuilder("近轮对话要点: ");
        for (int i = 0; i < recent.size(); i++) {
            AiMessageDO message = recent.get(i);
            if (i > 0) {
                context.append(" / ");
            }
            context.append("[").append(roleLabel(message.getRole())).append("] ")
                    .append(normalizeContent(message.getContent()));
        }
        return context.toString();
    }

    /**
     * 角色中文标签: USER → 用户, AI → 客服; 其余原样输出(防御性兜底)
     */
    private String roleLabel(String role) {
        if ("USER".equals(role)) {
            return "用户";
        }
        if ("AI".equals(role)) {
            return "客服";
        }
        return StrUtil.nullToEmpty(role);
    }

    /**
     * 消息内容规整: 换行折叠为空格 → 去首尾空白 → 取前 60 字(中文安全截断)
     */
    private String normalizeContent(String content) {
        String text = StrUtil.nullToEmpty(content).replaceAll("[\\r\\n]+", " ").trim();
        return StrUtil.maxLength(text, RECENT_CONTEXT_CONTENT_MAX_LENGTH);
    }

    private boolean containsAny(String message, List<String> keywords) {
        if (CollUtil.isEmpty(keywords)) {
            return false;
        }
        for (String keyword : keywords) {
            if (StrUtil.isNotBlank(keyword) && message.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

}
