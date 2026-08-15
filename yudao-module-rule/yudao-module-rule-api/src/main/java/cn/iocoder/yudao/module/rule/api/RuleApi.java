package cn.iocoder.yudao.module.rule.api;

/**
 * 规则引擎 对外 RPC 接口(Feign)
 * 其他模块通过 Feign 调用本接口, 实现位于 rule-server
 */
public interface RuleApi {

    /** 占位方法: 按领域替换为真实接口 */
    String evaluate(String ruleCode, String payload);

}
