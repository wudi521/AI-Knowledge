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
import cn.iocoder.yudao.module.evidence.service.prompt.PromptSupport;
import cn.iocoder.yudao.module.evidence.service.validation.DomainEvidenceValidationPolicyRegistry;
import cn.iocoder.yudao.module.model.api.ModelApi;
import cn.iocoder.yudao.module.model.api.dto.ModelChatReqDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 断言验证器：通用证据约束固定，行业附加规则由 DomainEvidenceValidationPolicy 注入。 */
@Slf4j
@Component
public class ClaimVerifier {

    private static final String SYSTEM_PROMPT = """
            你是证据支撑核查员。将回答逐句拆分为断言, 并对照证据判定每句是否有证据支撑。
            只输出 JSON: {"claims":[{"text":"句子原文","verdict":"SUPPORTED|UNSUPPORTED","evidenceIndex":0}]}
            要求:
            1. 事实性断言必须由证据支撑；SUPPORTED 必须指向实际 evidenceIndex；
            2. [C1] 对应 evidenceIndex=0, [C2] 对应 1；
            2.1. 对每个断言优先检查回答中 [Cx] 标注对应的证据是否确实包含该事实(含数值/比例/实体/编号);
                若 [Cx] 指向的证据不含该内容而另一证据包含, 判定为引用错位(UNSUPPORTED);
                不得因单个断言的单个错误标注就要求整句重写, 而应在 evidenceIndex 上指出真正缺少支撑;
            3. 信息等价即可支撑，允许措辞/语序/格式不同，但实体名称、编号、人名、数值、日期不得凭空改变；
            4. 衔接句不计入断言；
            5. “依据当前资料无法确认/无法确定/不能据此确认”等证据边界或谨慎性表述不是外部事实断言，
               当它表达的是“现有证据不足以证明某结论”时视为允许的 EPISTEMIC_LIMITATION，不应作为幻觉阻断；
               可以输出 SUPPORTED，evidenceIndex=-1；
            6. 真正新增的事实若证据中找不到，一律 UNSUPPORTED。
            """;

    private static final int CONTENT_MAX_LEN = 300;

    @Resource private ModelApi modelApi;
    @Resource private PromptSupport promptSupport;
    @SuppressWarnings("unused")
    private final EvidenceProperties properties;
    private final DomainEvidenceValidationPolicyRegistry domainValidationPolicies;

    @Autowired
    public ClaimVerifier(EvidenceProperties properties,
                         DomainEvidenceValidationPolicyRegistry domainValidationPolicies) {
        this.properties = properties;
        this.domainValidationPolicies = domainValidationPolicies;
    }

    /** 兼容纯单元测试/旧调用；生产 Spring 路径使用插件构造器。 */
    public ClaimVerifier(EvidenceProperties properties) {
        this(properties, null);
    }

    public List<ClaimResult> verify(String query, String answer, List<Evidence> evidences) {
        return verify(query, answer, evidences, null);
    }

    public List<ClaimResult> verify(String query, String answer, List<Evidence> evidences, List<ChatTurnDTO> history) {
        try {
            if (StrUtil.isBlank(answer)) return null;
            ModelChatReqDTO req = new ModelChatReqDTO();
            req.setSystem(buildSystemPrompt(evidences));
            req.setUser(buildUserPrompt(query, answer, evidences, history));
            List<ClaimResult> claims = parseClaims(modelApi.chat(req).getCheckedData());
            if (claims == null) return null;
            int size = evidences != null ? evidences.size() : 0;
            for (ClaimResult claim : claims) {
                if (claim == null) continue;
                if (claim.getEvidenceIndex() != null && claim.getEvidenceIndex() >= size && claim.getEvidenceIndex() >= 0) {
                    claim.setEvidenceIndex(-1);
                }
                if ("SUPPORTED".equals(claim.getVerdict()) && Integer.valueOf(-1).equals(claim.getEvidenceIndex())
                        && !isEpistemicLimitation(claim.getText())) {
                    log.info("[verify][SUPPORTED 断言无证据指向, 降级 UNSUPPORTED: {}]", StrUtil.maxLength(claim.getText(), 100));
                    claim.setVerdict("UNSUPPORTED");
                }
                if (isEpistemicLimitation(claim.getText())) {
                    claim.setVerdict("SUPPORTED");
                    if (claim.getEvidenceIndex() == null) claim.setEvidenceIndex(-1);
                }
            }
            return claims;
        } catch (Exception e) {
            log.warn("[verify][断言验证调用异常, 返回 null: {}]", e.getMessage());
            return null;
        }
    }

    public boolean allSupported(List<ClaimResult> claims) {
        if (claims == null || claims.isEmpty()) return false;
        for (ClaimResult claim : claims) {
            if (claim == null) return false;
            if (isEpistemicLimitation(claim.getText())) continue;
            if (!"SUPPORTED".equals(claim.getVerdict())) return false;
        }
        return true;
    }

    private String buildSystemPrompt(List<Evidence> evidences) {
        String base = promptSupport.get("claim-verify", SYSTEM_PROMPT);
        if (domainValidationPolicies == null) return base;
        List<String> rules = domainValidationPolicies.claimVerificationRules(evidences);
        if (rules.isEmpty()) return base;
        StringBuilder sb = new StringBuilder(StrUtil.nullToEmpty(base));
        sb.append("\n领域附加核查规则（只能加严/澄清，不能绕过通用证据约束）：\n");
        for (int i = 0; i < rules.size(); i++) sb.append("D").append(i + 1).append(". ").append(rules.get(i)).append('\n');
        return sb.toString();
    }

    private boolean isEpistemicLimitation(String text) {
        if (StrUtil.isBlank(text)) return false;
        String t = text.replace(" ", "");
        return t.contains("无法确认") || t.contains("无法确定") || t.contains("不能确认")
                || t.contains("不能据此") || t.contains("不足以确认") || t.contains("不足以证明")
                || t.contains("不能证明") || t.contains("无法据此") || t.contains("未提供足够证据")
                || t.contains("仅凭") && (t.contains("不能") || t.contains("无法"));
    }

    private String buildUserPrompt(String query, String answer, List<Evidence> evidences, List<ChatTurnDTO> history) {
        StringBuilder sb = new StringBuilder();
        String historyText = ContextFormatter.formatHistory(history);
        if (StrUtil.isNotBlank(historyText)) sb.append(historyText).append("\n\n");
        sb.append("问题: ").append(query).append("\n\n待核查回答:\n").append(answer).append("\n\n证据列表:\n");
        if (evidences != null) {
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
        }
        return sb.toString();
    }

    private List<ClaimResult> parseClaims(String resp) {
        try {
            if (StrUtil.isBlank(resp)) return null;
            String jsonText = JsonExtract.extractObject(resp);
            if (jsonText == null) return null;
            JSONArray claims = JSONUtil.parseObj(jsonText).getJSONArray("claims");
            if (claims == null || claims.isEmpty()) return null;
            List<ClaimResult> result = new ArrayList<>();
            for (int i = 0; i < claims.size(); i++) {
                ClaimResult claim = parseEntry(claims.getJSONObject(i));
                if (claim != null) result.add(claim);
            }
            return result.isEmpty() ? null : result;
        } catch (Exception e) {
            log.warn("[verify][断言结果解析失败, 返回 null: {}]", e.getMessage());
            return null;
        }
    }

    private ClaimResult parseEntry(JSONObject entry) {
        if (entry == null) return null;
        try {
            return ClaimResult.builder()
                    .text(entry.getStr("text", ""))
                    .verdict(normalizeVerdict(entry.getStr("verdict", "")))
                    .evidenceIndex(parseIndex(entry))
                    .build();
        } catch (Exception e) {
            return null;
        }
    }

    private String normalizeVerdict(String raw) {
        if (StrUtil.isBlank(raw)) return "UNSUPPORTED";
        return "SUPPORTED".equals(raw.trim().toUpperCase(Locale.ROOT)) ? "SUPPORTED" : "UNSUPPORTED";
    }

    private int parseIndex(JSONObject entry) {
        try {
            Integer index = entry.getInt("evidenceIndex", -1);
            return index != null ? index : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private String truncate(String content) {
        if (content == null) return "";
        return content.length() <= CONTENT_MAX_LEN ? content : content.substring(0, CONTENT_MAX_LEN) + "…";
    }
}
