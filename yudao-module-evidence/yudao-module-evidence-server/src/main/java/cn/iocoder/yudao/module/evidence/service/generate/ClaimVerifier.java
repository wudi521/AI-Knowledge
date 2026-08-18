package cn.iocoder.yudao.module.evidence.service.generate;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
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
            4. 若句子是"根据现有资料无法确定"这类如实说明证据不足的结论, 且证据确实无法回答该问题, 判定为 SUPPORTED。
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
     * 逐句断言验证(单次 LLM 调用)
     *
     * @param query     用户问题(供"证据确实无法回答"类判定参考)
     * @param answer    待核查的回答(LLM 生成, 含 [C1]..[CN] 引用)
     * @param evidences 证据列表(与生成器入参一致; evidenceIndex 为 0 起位置)
     * @return 断言列表; 回答空白/调用异常/解析失败 → null
     */
    public List<ClaimResult> verify(String query, String answer, List<Evidence> evidences) {
        try {
            if (StrUtil.isBlank(answer)) {
                log.warn("[verify][回答为空, 无法验证, 返回 null]");
                return null;
            }
            ModelChatReqDTO req = new ModelChatReqDTO();
            req.setSystem(SYSTEM_PROMPT);
            req.setUser(buildUserPrompt(query, answer, evidences));
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

    /** 组装用户提示词: 问题 + 待核查回答 + 证据列表(格式与生成器完全一致, 保证 [Ci] 编号对应) */
    private String buildUserPrompt(String query, String answer, List<Evidence> evidences) {
        StringBuilder sb = new StringBuilder();
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
