package cn.iocoder.yudao.module.model.api;

/**
 * 模型网关 对外 RPC 接口(Feign)
 * 其他模块通过 Feign 调用本接口, 实现位于 model-server
 */
public interface ModelApi {

    /** 占位方法: 按领域替换为真实接口 */
    String chat(String model, String prompt);

}
