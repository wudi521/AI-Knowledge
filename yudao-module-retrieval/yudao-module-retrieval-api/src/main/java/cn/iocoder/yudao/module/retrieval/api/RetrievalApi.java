package cn.iocoder.yudao.module.retrieval.api;

/**
 * 检索平台 对外 RPC 接口(Feign)
 * 其他模块通过 Feign 调用本接口, 实现位于 retrieval-server
 */
public interface RetrievalApi {

    /** 占位方法: 按领域替换为真实接口 */
    java.util.List<String> search(Long tenantId, String query);

}
