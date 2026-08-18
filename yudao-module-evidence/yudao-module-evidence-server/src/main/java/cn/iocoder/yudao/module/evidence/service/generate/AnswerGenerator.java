package cn.iocoder.yudao.module.evidence.service.generate;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.evidence.api.dto.ChatTurnDTO;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.model.api.ModelApi;
import cn.iocoder.yudao.module.model.api.dto.ModelChatReqDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 回答生成器: 基于证据列表生成带 [C1]..[CN] 引用的回答(LLM 单次调用)
 * <p>
 * 引用约定: [Ci] 的编号 = 证据在输入列表中的位置(1 起, 与检索模块 SearchService 的
 * [C1]..[CN] 语义一致), 与 {@link ClaimVerifier} 输出的 0 起 evidenceIndex 相差 1:
 * [C1] ↔ evidenceIndex=0。
 * <p>
 * 健壮性: 任何异常/空白回答 → 返回 null(由编排器决定重试或失败), 绝不抛出。
 */
@Slf4j
@Component
public class AnswerGenerator {

    /** 系统提示词: 只依据证据作答 + 每个事实点标注引用 + 证据不足明说 */
    private static final String SYSTEM_PROMPT = """
            你是企业客服知识库的回答生成器。只依据下方证据回答问题, 不得引入证据之外的信息。
            回答要求:
            ①直接回答用户问题, 简明扼要;
            ②每个事实点后标注引用编号, 如 [C1][C2](编号 = 证据序号, 从1开始, 与下方证据列表一一对应);
            ③若证据不足则明确说"根据现有资料无法确定";
            ④不要输出无实质内容的衔接引导句(如"您可以选择以下两种方式之一:"), 直接列事实点即可;
            ⑤若提供历史对话, 仅用于理解指代(那/它/多少钱), 回答仍只依据证据, 不要复述历史。
            """;

    /** 证据内容截断长度(字) */
    private static final int CONTENT_MAX_LEN = 300;

    @Resource
    private ModelApi modelApi;

    /**
     * 生成回答(首次尝试)
     *
     * @param query     用户问题
     * @param evidences 证据列表(去重后、按得分降序; 编号 [C1]..[CN] 按列表位置 1 起)
     * @return 带引用回答; 证据为空/调用异常/回答空白 → null
     */
    public String generate(String query, List<Evidence> evidences) {
        return generate(query, evidences, (List<ChatTurnDTO>) null);
    }

    /**
     * 生成回答(重试版本: 追加上一轮验证反馈, 强制删除/改写无据句; 无历史上下文)
     *
     * @param retryFeedback 上一轮验证反馈(首轮为 null); 非空时追加到系统提示词并强调"无据句必须删除或改写为有据表述"
     * @return 带引用回答; 证据为空/调用异常/回答空白 → null
     */
    String generate(String query, List<Evidence> evidences, String retryFeedback) {
        return generate(query, evidences, null, retryFeedback);
    }

    /**
     * 生成回答(带历史上下文: 历史仅用于理解指代, 回答仍只依据证据)
     *
     * @param query     用户问题
     * @param evidences 证据列表(去重后、按得分降序; 编号 [C1]..[CN] 按列表位置 1 起)
     * @param history   上下文轮次(可选, null/空 = 单轮行为)
     * @return 带引用回答; 证据为空/调用异常/回答空白 → null
     */
    public String generate(String query, List<Evidence> evidences, List<ChatTurnDTO> history) {
        return generate(query, evidences, history, null);
    }

    /**
     * 生成回答(带历史上下文 + 重试反馈)
     *
     * @param retryFeedback 上一轮验证反馈(首轮为 null); 非空时追加到系统提示词并强调"无据句必须删除或改写为有据表述"
     * @return 带引用回答; 证据为空/调用异常/回答空白 → null
     */
    String generate(String query, List<Evidence> evidences, List<ChatTurnDTO> history, String retryFeedback) {
        try {
            if (evidences == null || evidences.isEmpty()) {
                log.warn("[generate][证据列表为空, 无法生成回答, 返回 null]");
                return null;
            }
            String system = StrUtil.isBlank(retryFeedback)
                    ? SYSTEM_PROMPT
                    : SYSTEM_PROMPT + "\n\n" + retryFeedback + "\n无据句必须删除或改写为有据表述。";
            ModelChatReqDTO req = new ModelChatReqDTO();
            req.setSystem(system);
            req.setUser(buildUserPrompt(query, evidences, history));
            String answer = modelApi.chat(req).getCheckedData();
            return StrUtil.isBlank(answer) ? null : answer.trim();
        } catch (Exception e) {
            log.warn("[generate][回答生成异常, 返回 null: {}]", e.getMessage());
            return null;
        }
    }

    /**
     * 组装用户提示词: (可选历史对话块) + 问题 + 证据列表(每条 "[Ci] 来源:文档名 版本号; 内容:...", 内容截断)。
     * 注意: 逐条渲染不跳过任何位置, 保证 [Ci] 编号与列表位置严格一致(供验证器 0 起索引回映)。
     * 历史块仅作指代理解上下文, 不参与证据支撑。
     */
    private String buildUserPrompt(String query, List<Evidence> evidences, List<ChatTurnDTO> history) {
        StringBuilder sb = new StringBuilder();
        String historyText = ContextFormatter.formatHistory(history);
        if (StrUtil.isNotBlank(historyText)) {
            sb.append(historyText).append("\n\n");
        }
        sb.append("问题: ").append(query).append("\n\n证据列表:\n");
        for (int i = 0; i < evidences.size(); i++) {
            Evidence evidence = evidences.get(i);
            sb.append("[C").append(i + 1).append("] 来源:")
                    .append(StrUtil.nullToEmpty(evidence != null ? evidence.getDocumentName() : null)).append(' ')
                    .append(StrUtil.nullToEmpty(evidence != null ? evidence.getVersionNo() : null))
                    .append("; 内容:").append(truncate(evidence != null ? evidence.getContent() : null)).append('\n');
        }
        return sb.toString();
    }

    /** 截断内容至 300 字(超出加省略号) */
    private String truncate(String content) {
        if (content == null) {
            return "";
        }
        if (content.length() <= CONTENT_MAX_LEN) {
            return content;
        }
        return content.substring(0, CONTENT_MAX_LEN) + "…";
    }

}
