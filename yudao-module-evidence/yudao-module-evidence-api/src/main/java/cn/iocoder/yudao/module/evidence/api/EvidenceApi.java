package cn.iocoder.yudao.module.evidence.api;

/**
 * 证据平台 对外 RPC 接口(Feign)
 * 其他模块通过 Feign 调用本接口, 实现位于 evidence-server
 */
public interface EvidenceApi {

    /** 占位方法: 按领域替换为真实接口 */
    String verifyClaim(String claim, java.util.List<String> chunkIds);

}
