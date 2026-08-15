package cn.iocoder.yudao.module.eval.api;

/**
 * 评测平台 对外 RPC 接口(Feign)
 * 其他模块通过 Feign 调用本接口, 实现位于 eval-server
 */
public interface EvalApi {

    /** 占位方法: 按领域替换为真实接口 */
    Boolean runEval(Long taskId);

}
