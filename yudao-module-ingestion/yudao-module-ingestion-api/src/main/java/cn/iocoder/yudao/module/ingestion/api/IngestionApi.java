package cn.iocoder.yudao.module.ingestion.api;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.ingestion.api.dto.ChunkRespDTO;
import cn.iocoder.yudao.module.ingestion.enums.ApiConstants;
import feign.FeignIgnore;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
/**
 * 入库管线 对外 RPC 接口(Feign)
 * 其他模块通过 Feign 调用本接口, 实现位于 ingestion-server
 */
@FeignClient(name = ApiConstants.NAME)
public interface IngestionApi {

    /** 占位方法: 按领域替换为真实接口 */
    @FeignIgnore
    Boolean triggerIngest(Long documentId);

    /**
     * 删除文档关联的全部数据(MySQL ai_chunk + ES 索引 + Milvus 向量)
     *
     * @param documentId 文档编号(ai_chunk.version_id)
     * @return 是否成功
     */
    @PostMapping(ApiConstants.PREFIX + "/delete-document-data")
    CommonResult<Boolean> deleteDocumentData(@RequestParam("documentId") Long documentId);

    /**
     * 发布索引: 按版本从 MySQL 读 chunk + embedding, 写 Milvus/ES, 置 chunk PUBLISHED
     * <p>
     * 幂等契约(发布可重试): 同版本重复调用为"覆盖式"重写 Milvus/ES, 不产生重复数据;
     * "置 chunk PUBLISHED"必须是最后一步; 任一中间失败可安全重试。
     *
     * @param versionId 版本编号(ai_chunk.version_id)
     * @param kbId 知识库编号
     * @param tenantId 租户编号
     * @return 是否成功
     */
    @PostMapping(ApiConstants.PREFIX + "/index-version")
    CommonResult<Boolean> indexVersion(@RequestParam("versionId") Long versionId,
                                       @RequestParam("kbId") Long kbId,
                                       @RequestParam("tenantId") Long tenantId);

    /**
     * 按版本查询片段列表(供 knowledge 抽取审核条目)
     *
     * @param versionId 版本编号
     * @return 片段列表
     */
    @GetMapping(ApiConstants.PREFIX + "/get-chunks-by-version")
    CommonResult<List<ChunkRespDTO>> getChunksByVersion(@RequestParam("versionId") Long versionId);

}
