package cn.iocoder.yudao.module.evidence.service.generate;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.evidence.api.dto.ChatTurnDTO;
import cn.iocoder.yudao.module.evidence.domain.ClaimResult;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.framework.evidence.EvidenceProperties;
import cn.iocoder.yudao.module.evidence.service.conflict.JsonExtract;
import cn.iocoder.yudao.module.model.api.ModelApi;
import cn.iocoder.yudao.module.model.api.dto.ModelChatReqDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 断言验证器: 将回答逐句拆分为断言, 对照证据判定每句是否有支撑(LLM 单次调用 + 结构化 JSON)
 * <p>
 * 索引约定: 回答中的 [C1] 对应证据列表第 0 条(evidenceIndex=0), [C2] 对应第 1 条, 依此类推;
 * 无支撑断言 evidenceIndex = -1。
 * <p>
 * 健壮性: 调用异常/响应空白/JSON 解析失败 → 返回 null(由编排器按一次失败尝试处理), 绝不抛出。
 */
@Slf4j
@Component
public class ClaimVerifier {

    /** 系统提示词: 逐句拆分断言 + 判定支撑 + 只输出固定 JSON */
    private static final String SYSTEM_PROMPT = """
            你是证据支撑核查员。将回答逐句拆分为断言, 并对照证据判定每句是否有证据支撑。
            只输出 JSON, 不要输出任何其他文字。JSON 格式固定为:
            {"claims":[{"text":"句子原文","verdict":"SUPPORTED|UNSUPPORTED","evidenceIndex":0}]}
            要求:
            1. 将回答按句子拆分, 每句一条记录, text 填句子原文;
            2. verdict: 有证据支撑为 SUPPORTED, 无证据支撑为 UNSUPPORTED;
            3. evidenceIndex: 支撑该句的证据在证据列表中的序号(0起; 无支撑给 -1);
               回答中的引用 [C1] 对应 evidenceIndex=0, [C2] 对应 evidenceIndex=1, 依此类推;
            4. 若句子是"根据现有资料无法确定"这类如实说明证据不足的结论, 且证据确实无法回答该问题, 判定为 SUPPORTED;
            5. 衔接/过渡短语(如"您可以选择以下两种方式之一:""具体如下:""综上:"等不陈述事实的引导句)不算断言, 直接判定为 SUPPORTED;
            6. 只有携带具体事实的断言(如价格/期限/次数/政策条款)才需要证据支撑, 无支撑才判 UNSUPPORTED;
            7. 若提供历史对话, 仅用于理解指代, 断言支撑判定只看证据列表。
            """;

    /** 证据内容截断长度(字) */
    private static final int CONTENT_MAX_LEN = 300;

    @Resource
    private ModelApi modelApi;

    /**
     * 证据业务配置(构造注入; 当前 claim.max-retry 由编排器消费, 此处预留 Claim 相关配置扩展位)
     */
    private final EvidenceProperties properties;

    public ClaimVerifier(EvidenceProperties properties) {
        this.properties = properties;
    }

    /**
     * 逐句断言验证(单次 LLM 调用; 无历史上下文)
     *
     * @param query     用户问题(供"证据确实无法回答"类判定参考)
     * @param answer    待核查的回答(LLM 生成, 含 [C1]..[CN] 引用)
     * @param evidences 证据列表(与生成器入参一致; evidenceIndex 为 0 起位置)
     * @return 断言列表; 回答空白/调用异常/解析失败 → null
     */
    public List<ClaimResult> verify(String query, String answer, List<Evidence> evidences) {
        return verify(query, answer, evidences, null);
    }

    /**
     * 逐句断言验证(单次 LLM 调用; 支持历史上下文: 历史仅用于理解指代, 支撑判定只看证据列表)
     *
     * @param query     用户问题(供"证据确实无法回答"类判定参考)
     * @param answer    待核查的回答(LLM 生成, 含 [C1]..[CN] 引用)
     * @param evidences 证据列表(与生成器入参一致; evidenceIndex 为 0 起位置)
     * @param history   上下文轮次(可选, null/空 = 单轮行为)
     * @return 断言列表; 回答空白/调用异常/解析失败 → null
     */
    public List<ClaimResult> verify(String query, String answer, List<Evidence> evidences, List<ChatTurnDTO> history) {
        try {
            if (StrUtil.isBlank(answer)) {
                log.warn("[verify][回答为空, 无法验证, 返回 null]");
                return null;
            }
            ModelChatReqDTO req = new ModelChatReqDTO();
            req.setSystem(SYSTEM_PROMPT);
            req.setUser(buildUserPrompt(query, answer, evidences, history));
            String resp = modelApi.chat(req).getCheckedData();
            List<ClaimResult> claims = parseClaims(resp);
            if (claims == null) {
                return null;
            }
            // 越界索引钳制: LLM 给出的 evidenceIndex 超出证据列表范围视为模型错误, 置 -1 并告警
            int size = evidences != null ? evidences.size() : 0;
            for (ClaimResult claim : claims) {
                if (claim != null && claim.getEvidenceIndex() != null
                        && claim.getEvidenceIndex() >= size && claim.getEvidenceIndex() >= 0) {
                    log.warn("[verify][断言 evidenceIndex={} 超出证据范围({}), 钳制为 -1: {}]",
                            claim.getEvidenceIndex(), size, StrUtil.maxLength(claim.getText(), 100));
                    claim.setEvidenceIndex(-1);
                }
                // 结构化兜底: 被判 UNSUPPORTED 且无证据指向(-1)的句子, 若不含事实性标记(数字/价格/日期/百分比/货币),
                // 视为"衔接/过渡短语"(如"您可以选择以下两种方式之一:"), 不构成无据断言, 放行为 SUPPORTED。
                // 依据: 无据断言必须携带具体事实; 纯引导句不会误导客户, 拦截它只会浪费重试次数。
                if (claim != null && !"SUPPORTED".equals(claim.getVerdict())
                        && claim.getEvidenceIndex() != null && claim.getEvidenceIndex() == -1
                        && !containsFactualMarker(claim.getText())) {
                    log.info("[verify][无据句判定为衔接短语, 放行: {}]", StrUtil.maxLength(claim.getText(), 100));
                    claim.setVerdict("SUPPORTED");
                }
            }
            return claims;
        } catch (Exception e) {
            log.warn("[verify][断言验证调用异常, 返回 null: {}]", e.getMessage());
            return null;
        }
    }

    /**
     * 是否全部断言均被支撑: 非空 且 每条 verdict 均为 SUPPORTED
     */
    public boolean allSupported(List<ClaimResult> claims) {
        if (claims == null || claims.isEmpty()) {
            return false;
        }
        for (ClaimResult claim : claims) {
            if (claim == null || !"SUPPORTED".equals(claim.getVerdict())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断句子是否含"事实性标记"(数字/价格/日期/百分比/货币等具体信息)。
     * 用于区分"无据断言"(必须携带具体事实, 如"免费 3 次")与"衔接/过渡短语"(纯引导, 如"您可以选择以下方式").
     */
    private boolean containsFactualMarker(String text) {
        if (StrUtil.isBlank(text)) {
            return false;
        }
        // 阿拉伯数字(含小数/千分位)
        if (text.matches(".*[0-9]+.*")) {
            return true;
        }
        // 中文数字(一~十 百千万)
        if (text.matches(".*[一二三四五六七八九十百千万]+.*")) {
            return true;
        }
        // 货币/百分比/折/期限单位
        return text.matches(".*[¥￥%折].*")
                || text.matches(".*(元|次|天|年|月|日|小时|分钟|工作日).*");
    }

    /** 组装用户提示词: (可选历史对话块) + 问题 + 待核查回答 + 证据列表(格式与生成器完全一致, 保证 [Ci] 编号对应) */
    private String buildUserPrompt(String query, String answer, List<Evidence> evidences, List<ChatTurnDTO> history) {
        StringBuilder sb = new StringBuilder();
        String historyText = ContextFormatter.formatHistory(history);
        if (StrUtil.isNotBlank(historyText)) {
            sb.append(historyText).append("\n\n");
        }
        sb.append("问题: ").append(query).append("\n\n待核查回答:\n").append(answer).append("\n\n证据列表:\n");
        if (evidences != null) {
            for (int i = 0; i < evidences.size(); i++) {
                Evidence evidence = evidences.get(i);
                sb.append("[C").append(i + 1).append("] 来源:")
                        .append(StrUtil.nullToEmpty(evidence != null ? evidence.getDocumentName() : null)).append(' ')
                        .append(StrUtil.nullToEmpty(evidence != null ? evidence.getVersionNo() : null))
                        .append("; 内容:").append(truncate(evidence != null ? evidence.getContent() : null)).append('\n');
            }
        }
        return sb.toString();
    }

    /** 解析 LLM 输出: 提取 JSON(容忍围栏/前后缀) → 逐条解析; 任何失败 → null */
    private List<ClaimResult> parseClaims(String resp) {
        try {
            if (StrUtil.isBlank(resp)) {
                log.warn("[verify][LLM 响应为空, 返回 null]");
                return null;
            }
            String jsonText = JsonExtract.extractObject(resp);
            if (jsonText == null) {
                log.warn("[verify][LLM 响应未包含 JSON 对象, 返回 null: {}]", StrUtil.maxLength(resp, 200));
                return null;
            }
            JSONObject root = JSONUtil.parseObj(jsonText);
            JSONArray claims = root.getJSONArray("claims");
            if (claims == null || claims.isEmpty()) {
                log.warn("[verify][LLM 响应缺少 claims 数组, 返回 null]");
                return null;
            }
            List<ClaimResult> result = new ArrayList<>();
            for (int i = 0; i < claims.size(); i++) {
                ClaimResult claim = parseEntry(claims.getJSONObject(i));
                if (claim != null) {
                    result.add(claim);
                }
            }
            if (result.isEmpty()) {
                log.warn("[verify][claims 数组无有效条目, 返回 null]");
                return null;
            }
            return result;
        } catch (Exception e) {
            log.warn("[verify][断言结果解析失败, 返回 null: {}]", e.getMessage());
            return null;
        }
    }

    /** 解析单条断言: 字段缺省兜底 + verdict 归一化; 单条失败跳过 */
    private ClaimResult parseEntry(JSONObject entry) {
        if (entry == null) {
            return null;
        }
        try {
            String text = entry.getStr("text", "");
            String verdict = normalizeVerdict(entry.getStr("verdict", ""));
            int evidenceIndex = parseIndex(entry);
            return ClaimResult.builder()
                    .text(text)
                    .verdict(verdict)
                    .evidenceIndex(evidenceIndex)
                    .build();
        } catch (Exception e) {
            log.warn("[verify][单条断言解析失败, 跳过: {}]", e.getMessage());
            return null;
        }
    }

    /** verdict 归一化: 大小写不敏感, 仅 "SUPPORTED" 视为有支撑, 其余(含缺省/非法)一律 UNSUPPORTED */
    private String normalizeVerdict(String raw) {
        if (StrUtil.isBlank(raw)) {
            return "UNSUPPORTED";
        }
        return "SUPPORTED".equals(raw.trim().toUpperCase(Locale.ROOT)) ? "SUPPORTED" : "UNSUPPORTED";
    }

    /** evidenceIndex 解析: 缺省/非法 → -1 */
    private int parseIndex(JSONObject entry) {
        try {
            Integer index = entry.getInt("evidenceIndex", -1);
            return index != null ? index : -1;
        } catch (Exception e) {
            return -1;
        }
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
