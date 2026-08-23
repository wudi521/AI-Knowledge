package cn.iocoder.yudao.module.evidence.service.generate;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.evidence.api.dto.ChatTurnDTO;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.service.prompt.PromptSupport;
import cn.iocoder.yudao.module.model.api.ModelApi;
import cn.iocoder.yudao.module.model.api.dto.ModelChatReqDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 回答生成器: 基于证据列表生成带 [C1]..[CN] 引用的回答。
 */
@Slf4j
@Component
public class AnswerGenerator {

    private static final String GENERAL_SYSTEM_PROMPT = """
            你是企业知识库的回答生成器。只依据下方证据回答问题, 不得引入证据之外的信息。
            回答要求:
            ①直接回答用户问题, 简明扼要;
            ②每个事实点后标注引用编号, 如 [C1][C2](编号 = 证据序号, 从1开始, 与下方证据列表一一对应);
            ③若证据不足则明确说"根据现有资料无法确定";
            ④不要输出无实质内容的衔接引导句(如"您可以选择以下两种方式之一:"), 直接列事实点即可;
            ⑤事实点尽量沿用证据原文的表述与数据(如著录信息 "(71)申请人 韩信" 就写 "申请人为韩信",
              公布号/申请号/编号/人名/数值等保持与证据完全一致), 避免改写导致与证据不一致;
            ⑥若提供历史对话, 仅用于理解指代(那/它/多少钱), 回答仍只依据证据, 不要复述历史。
            """;

    private static final String PATENT_SYSTEM_PROMPT = """
            你是专利公开文献知识库的回答生成器。只依据下方专利文献证据回答，不得引入证据之外的信息。
            回答要求:
            1. 直接回答问题，每个事实点必须标注 [C1][C2] 等证据编号；
            2. 区分“专利文献记载/声称”与已经被外部事实验证的结论；不得把申请文件中的表述当成已证实事实；
            3. 公开申请文本不能单独证明已经获得授权。若证据中没有明确法律状态，回答“依据当前知识库资料无法确认是否已授权”；
            4. 涉及医疗、健康、疗效、安全性或科学效果时，只能表述为“该文献记载/声称……”，不得据此确认临床有效性、安全性或真实性；
            5. 对权利要求概括问题, 只概括问题所指定权利要求的内容, 忠实于权利要求原文: 不得添加权利要求中不存在的限制,
               不得解释法律效力、不得推断授权范围, 不得引用其它专利内容; 若问题指定"原文", 则直接给出权利要求原文；
            6. 若证据不足，明确说“依据当前知识库资料无法确定”；
            7. 若提供历史对话，仅用于理解指代，答案事实仍只能来自证据。
            """;

    private static final int CONTENT_MAX_LEN = 500;

    @Resource
    private ModelApi modelApi;
    @Resource
    private PromptSupport promptSupport;

    public String generate(String query, List<Evidence> evidences) {
        return generate(query, evidences, (List<ChatTurnDTO>) null);
    }

    String generate(String query, List<Evidence> evidences, String retryFeedback) {
        return generate(query, evidences, null, retryFeedback);
    }

    public String generate(String query, List<Evidence> evidences, List<ChatTurnDTO> history) {
        return generate(query, evidences, history, null);
    }

    String generate(String query, List<Evidence> evidences, List<ChatTurnDTO> history, String retryFeedback) {
        try {
            if (evidences == null || evidences.isEmpty()) {
                log.warn("[generate][证据列表为空, 无法生成回答, 返回 null]");
                return null;
            }
            boolean patent = isPatentEvidence(evidences);
            String key = patent ? "answer-generate-patent" : "answer-generate";
            String fallback = patent ? PATENT_SYSTEM_PROMPT : GENERAL_SYSTEM_PROMPT;
            String basePrompt = promptSupport.get(key, fallback);
            String system = StrUtil.isBlank(retryFeedback)
                    ? basePrompt
                    : basePrompt + "\n\n" + retryFeedback + "\n无据句必须删除或改写为有据表述。";
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

    private boolean isPatentEvidence(List<Evidence> evidences) {
        for (Evidence evidence : evidences) {
            if (evidence == null || StrUtil.isBlank(evidence.getChunkMetadata())) continue;
            try {
                JSONObject meta = JSONUtil.parseObj(evidence.getChunkMetadata());
                if ("PATENT".equalsIgnoreCase(meta.getStr("domainCode"))) return true;
            } catch (Exception ignore) {
                // 非法元数据继续按通用提示词
            }
        }
        return false;
    }

    private String buildUserPrompt(String query, List<Evidence> evidences, List<ChatTurnDTO> history) {
        StringBuilder sb = new StringBuilder();
        String historyText = ContextFormatter.formatHistory(history);
        if (StrUtil.isNotBlank(historyText)) sb.append(historyText).append("\n\n");
        sb.append("问题: ").append(query).append("\n\n证据列表:\n");
        for (int i = 0; i < evidences.size(); i++) {
            Evidence evidence = evidences.get(i);
            sb.append("[C").append(i + 1).append("] 来源:")
                    .append(StrUtil.nullToEmpty(evidence != null ? evidence.getDocumentName() : null)).append(' ')
                    .append(StrUtil.nullToEmpty(evidence != null ? evidence.getVersionNo() : null));
            if (evidence != null && StrUtil.isNotBlank(evidence.getChunkMetadata())) {
                sb.append("; 元数据:").append(StrUtil.maxLength(evidence.getChunkMetadata(), 500));
            }
            sb.append("; 内容:").append(truncate(evidence != null ? evidence.getContent() : null)).append('\n');
        }
        return sb.toString();
    }

    private String truncate(String content) {
        if (content == null) return "";
        return content.length() <= CONTENT_MAX_LEN ? content : content.substring(0, CONTENT_MAX_LEN) + "…";
    }
}
