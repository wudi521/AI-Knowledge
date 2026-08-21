package cn.iocoder.yudao.module.evidence.service.rule;

import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.rule.api.RuleApi;
import cn.iocoder.yudao.module.rule.api.dto.RuleEvaluateReqDTO;
import cn.iocoder.yudao.module.rule.api.dto.RuleEvaluateRespDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 硬规则优先短路: 命中规则 → 直接给规则结论(不走检索/LLM), 未命中/RPC 失败 → null(走原管线)
 */
@Slf4j
@Component
public class RuleShortCircuit {

    /** 默认规则键(evidence 管线统一用; 规则内容按租户/场景配置) */
    public static final String DEFAULT_RULE_KEY = "default";

    @Resource
    private RuleApi ruleApi;

    /**
     * 评估硬规则
     *
     * @param query 客户问题
     * @param facts 规则事实(键值对; 本轮仅 query, 槽位值接入留后续)
     * @return 命中结论(取第一条); 未命中/RPC 失败 → null
     */
    public RuleConclusion evaluate(String query, Map<String, Object> facts) {
        try {
            LoginUser loginUser = SecurityFrameworkUtils.getLoginUser();
            Long tenantId = loginUser != null ? loginUser.getTenantId() : null;
            RuleEvaluateReqDTO req = new RuleEvaluateReqDTO();
            req.setRuleKey(DEFAULT_RULE_KEY);
            req.setTenantId(tenantId);
            req.setFacts(facts);
            RuleEvaluateRespDTO resp = ruleApi.evaluate(req).getCheckedData();
            if (resp == null || !Boolean.TRUE.equals(resp.getMatched())
                    || resp.getConclusions() == null || resp.getConclusions().isEmpty()) {
                return null;
            }
            RuleEvaluateRespDTO.Conclusion c = resp.getConclusions().get(0);
            return new RuleConclusion(c.getCode(), c.getText());
        } catch (Exception e) {
            log.warn("[evaluate][规则评估失败, 忽略规则走原管线: {}]", e.getMessage());
            return null;
        }
    }

    /** 规则命中结论 */
    public record RuleConclusion(String code, String text) {
    }
}
