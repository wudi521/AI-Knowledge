package cn.iocoder.yudao.module.rule.api;

import cn.iocoder.yudao.module.rule.api.RuleApi;
import org.springframework.stereotype.Service;

/**
 * 规则引擎 对外 RPC 实现
 */
@Service
public class RuleApiImpl implements RuleApi {

    @Override
    public String evaluate(String ruleCode, String payload) {
        return "{}";
    }

}
