package cn.iocoder.yudao.module.ingestion.api;

/**
 * 入库管线 对外 RPC 接口(Feign)
 * 其他模块通过 Feign 调用本接口, 实现位于 ingestion-server
 */
public interface IngestionApi {

    /** 占位方法: 按领域替换为真实接口 */
    Boolean triggerIngest(Long documentId);

}
