package cn.iocoder.yudao.module.rule.api;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.rule.api.dto.RuleEvaluateReqDTO;
import cn.iocoder.yudao.module.rule.api.dto.RuleEvaluateRespDTO;
import cn.iocoder.yudao.module.rule.dal.dataobject.rule.AiRuleDO;
import cn.iocoder.yudao.module.rule.dal.dataobject.rule.AiRuleHitDO;
import cn.iocoder.yudao.module.rule.dal.mysql.rule.AiRuleHitMapper;
import cn.iocoder.yudao.module.rule.dal.mysql.rule.AiRuleMapper;
import cn.iocoder.yudao.module.rule.service.rule.RuleEngine;
import cn.iocoder.yudao.module.rule.service.rule.RuleResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 规则引擎 对外 RPC 实现
 * <p>
 * 原则: 绝不抛出(失败 → error CommonResult, 调用方忽略规则走原链路); 命中 → ai_rule_hit 留痕
 */
@Slf4j
@RestController // Feign RPC 实现
@Validated
public class RuleApiImpl implements RuleApi {

    /** 全量启用状态 */
    private static final int STATUS_ENABLED = 1;

    @Resource
    private RuleEngine ruleEngine;
    @Resource
    private AiRuleMapper aiRuleMapper;
    @Resource
    private AiRuleHitMapper aiRuleHitMapper;

    @Override
    public CommonResult<RuleEvaluateRespDTO> evaluate(RuleEvaluateReqDTO req) {
        if (req == null || StrUtil.isBlank(req.getRuleKey())) {
            return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "ruleKey 不能为空");
        }
        try {
            Long tenantId = req.getTenantId() != null ? req.getTenantId() : TenantContextHolder.getTenantId();
            List<RuleResult> results = ruleEngine.evaluate(req.getRuleKey(), tenantId, req.getFacts());
            // 组装响应
            RuleEvaluateRespDTO resp = new RuleEvaluateRespDTO();
            resp.setMatched(!results.isEmpty());
            List<RuleEvaluateRespDTO.Conclusion> conclusions = results.stream().map(r -> {
                RuleEvaluateRespDTO.Conclusion c = new RuleEvaluateRespDTO.Conclusion();
                c.setCode(r.getCode());
                c.setText(r.getText());
                return c;
            }).collect(Collectors.toList());
            resp.setConclusions(conclusions);
            // 命中留痕(失败不影响主链路)
            if (!results.isEmpty()) {
                recordHit(req, tenantId, results);
            }
            return success(resp);
        } catch (Exception e) {
            log.error("[evaluate][ruleKey({}) 规则评估异常, 返回 error 供调用方忽略]", req.getRuleKey(), e);
            return CommonResult.error(GlobalErrorCodeConstants.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 命中留痕: ai_rule_hit 按租户记录(事实/结论 JSON), 失败仅 warn 不抛出
     */
    private void recordHit(RuleEvaluateReqDTO req, Long tenantId, List<RuleResult> results) {
        try {
            TenantUtils.execute(tenantId, () -> {
                AiRuleHitDO hit = new AiRuleHitDO();
                hit.setRuleKey(req.getRuleKey());
                hit.setRuleVersion(resolveEnabledVersion(req.getRuleKey()));
                hit.setQuery(queryFromFacts(req.getFacts()));
                hit.setFacts(toJson(req.getFacts()));
                hit.setConclusion(toJson(results));
                hit.setDeviated(false); // LLM 冲突对比预留, 本期恒 false
                aiRuleHitMapper.insert(hit);
                return null;
            });
        } catch (Exception e) {
            log.warn("[recordHit][ruleKey({}) tenantId({}) 留痕失败]", req.getRuleKey(), tenantId, e);
        }
    }

    /**
     * 命中时解析启用版本(引擎内部已解析, 此处仅留痕用; 无启用行则 null)
     */
    private Integer resolveEnabledVersion(String ruleKey) {
        List<AiRuleDO> rows = aiRuleMapper.selectByKeyAndStatusIn(ruleKey, List.of(STATUS_ENABLED));
        return rows.isEmpty() ? null : rows.get(0).getVersion();
    }

    private String queryFromFacts(Map<String, Object> facts) {
        if (facts == null) {
            return null;
        }
        Object query = facts.get("query");
        if (query == null) {
            return null;
        }
        String text = String.valueOf(query);
        return text.length() > 500 ? text.substring(0, 500) : text;
    }

    private String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return JSONUtil.toJsonStr(obj);
        } catch (Exception e) {
            log.warn("[toJson][序列化失败]", e);
            return null;
        }
    }

}
