package cn.iocoder.yudao.module.knowledge.api;

import cn.iocoder.yudao.module.knowledge.enums.ApiConstants;
import org.springframework.cloud.openfeign.FeignClient;
/**
 * 知识平台 对外 RPC 接口(Feign)
 * 其他模块通过 Feign 调用本接口, 实现位于 knowledge-server
 */
@FeignClient(name = ApiConstants.NAME)
public interface KnowledgeApi {

    /** 占位方法: 按领域替换为真实接口 */
    Boolean checkKnowledgePermission(Long chunkId, Long userId);

}
