package cn.iocoder.yudao.module.knowledge.api;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.knowledge.enums.ApiConstants;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
/**
 * 知识平台 对外 RPC 接口(Feign)
 * 其他模块通过 Feign 调用本接口, 实现位于 knowledge-server
 */
@FeignClient(name = ApiConstants.NAME)
public interface KnowledgeApi {

    /** 占位方法: 按领域替换为真实接口 */
    Boolean checkKnowledgePermission(Long chunkId, Long userId);

    /**
     * 更新文档解析状态(ingestion-server 回写)
     *
     * @param documentId 文档编号
     * @param parseStatus 解析状态: PARSING / EMBEDDING / INDEXED / FAILED
     * @param chunkCount 切分片段数(INDEXED 时传, 其他阶段可传 null)
     * @param errorMsg 失败原因(成功传 null)
     */
    @GetMapping(ApiConstants.PREFIX + "/update-parse-status")
    CommonResult<Boolean> updateDocumentParseStatus(@RequestParam("documentId") Long documentId,
                                                    @RequestParam("parseStatus") String parseStatus,
                                                    @RequestParam(value = "chunkCount", required = false) Integer chunkCount,
                                                    @RequestParam(value = "errorMsg", required = false) String errorMsg);

}
