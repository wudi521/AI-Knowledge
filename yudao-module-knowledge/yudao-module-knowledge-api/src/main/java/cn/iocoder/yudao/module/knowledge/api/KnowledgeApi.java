package cn.iocoder.yudao.module.knowledge.api;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeDocumentRespDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeVersionRespDTO;
import cn.iocoder.yudao.module.knowledge.enums.ApiConstants;
import feign.FeignIgnore;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;
import java.util.Set;
/**
 * 知识平台 对外 RPC 接口(Feign)
 * 其他模块通过 Feign 调用本接口, 实现位于 knowledge-server
 */
@FeignClient(name = ApiConstants.NAME)
public interface KnowledgeApi {

    /** 占位方法: 按领域替换为真实接口 */
    @FeignIgnore
    Boolean checkKnowledgePermission(Long chunkId, Long userId);

    /**
     * 更新文档解析状态(ingestion-server 回写)
     *
     * @param documentId 文档编号
     * @param parseStatus 解析状态: PARSING / EMBEDDING / INDEXED / FAILED
     * @param chunkCount 切分片段数(INDEXED 时传, 其他阶段可传 null)
     * @param errorMsg 失败原因(成功传 null)
     */
    @PostMapping(ApiConstants.PREFIX + "/update-parse-status")
    CommonResult<Boolean> updateDocumentParseStatus(@RequestParam("documentId") Long documentId,
                                                    @RequestParam("parseStatus") String parseStatus,
                                                    @RequestParam(value = "chunkCount", required = false) Integer chunkCount,
                                                    @RequestParam(value = "errorMsg", required = false) String errorMsg);

    /**
     * 查询文档详情(供 ingestion-server 取元数据)
     *
     * @param id 文档编号
     * @return 文档详情(不存在返回 null)
     */
    @GetMapping(ApiConstants.PREFIX + "/get-document")
    CommonResult<KnowledgeDocumentRespDTO> getDocument(@RequestParam("id") Long id);

    /**
     * 解析完成通知(ingestion 管线 MySQL 落库后调用)
     * knowledge 侧执行: 拉取 chunk -> LLM 抽取审核条目 -> 分流(REVIEW 或自动发布)
     * <p>
     * 必须携带 versionId(ingestion 在管线开始时从 getDocument 取得), 不能按"最新版本"推断:
     * 否则旧版本的 Kafka 消息重投会绑定到新版本, 导致误发布
     *
     * @param documentId 文档编号
     * @param versionId 管线实际写入 chunk 的版本编号
     * @return 是否成功
     */
    @PostMapping(ApiConstants.PREFIX + "/notify-parsed")
    CommonResult<Boolean> notifyParsed(@RequestParam("documentId") Long documentId,
                                       @RequestParam("versionId") Long versionId);

    /**
     * 查询文档的全部版本编号(供 ingestion 级联删除/片段页过滤)
     *
     * @param docId 文档编号
     * @return 版本编号列表(按 id 倒序, 空则无版本)
     */
    @GetMapping(ApiConstants.PREFIX + "/get-doc-version-ids")
    CommonResult<List<Long>> getDocVersionIds(@RequestParam("docId") Long docId);

    /**
     * 批量查询版本信息(供片段页联表: versionId -> docId/versionNo)
     *
     * @param versionIds 版本编号列表
     * @return 版本编号 -> 版本信息
     */
    @PostMapping(ApiConstants.PREFIX + "/get-version-map")
    CommonResult<Map<Long, KnowledgeVersionRespDTO>> getVersionMap(@RequestBody List<Long> versionIds);

    /**
     * 批量查询文档详情(检索结果文档信息补全, 避免逐条 Feign)
     *
     * @param ids 文档编号列表
     * @return 文档编号 -> 文档详情(不存在的自动过滤)
     */
    @PostMapping(ApiConstants.PREFIX + "/get-document-map")
    CommonResult<Map<Long, KnowledgeDocumentRespDTO>> getDocumentMap(@RequestBody List<Long> ids);

    /**
     * 查询知识库切分策略(ingestion 按知识库配置切分; 不存在返回默认 ParentChild)
     *
     * @param kbId 知识库编号
     * @return 切分策略: Semantic/ParentChild/Table/FAQ/Policy
     */
    @GetMapping(ApiConstants.PREFIX + "/get-kb-strategy")
    CommonResult<String> getKnowledgeBaseStrategy(@RequestParam("kbId") Long kbId);

    /**
     * 查询用户可见的知识库编号集合(检索权限过滤用; super_admin 返回全部)
     *
     * @param userId 用户编号
     * @return 可见知识库编号集合
     */
    @GetMapping(ApiConstants.PREFIX + "/get-visible-kb-ids")
    CommonResult<Set<Long>> getVisibleKbIds(@RequestParam("userId") Long userId);

}
