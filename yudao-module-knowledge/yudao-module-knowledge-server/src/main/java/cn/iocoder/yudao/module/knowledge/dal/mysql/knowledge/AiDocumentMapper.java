package cn.iocoder.yudao.module.knowledge.dal.mysql.knowledge;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.knowledge.controller.admin.knowledge.vo.AiDocumentPageReqVO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.AiDocumentDO;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** AI 文档 Mapper */
@Mapper
public interface AiDocumentMapper extends BaseMapperX<AiDocumentDO> {

    default PageResult<AiDocumentDO> selectPage(AiDocumentPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<AiDocumentDO>()
                .eqIfPresent(AiDocumentDO::getKbId, reqVO.getKbId())
                .likeIfPresent(AiDocumentDO::getName, reqVO.getName())
                .eqIfPresent(AiDocumentDO::getParseStatus, reqVO.getParseStatus())
                .inIfPresent(AiDocumentDO::getKbId, reqVO.getKbIds())
                .orderByDesc(AiDocumentDO::getId));
    }

    default int updateParseStatus(Long id, String parseStatus, Integer chunkCount, String errorMsg) {
        return update(null, new LambdaUpdateWrapper<AiDocumentDO>()
                .eq(AiDocumentDO::getId, id)
                .set(AiDocumentDO::getParseStatus, parseStatus)
                .set(chunkCount != null, AiDocumentDO::getChunkCount, chunkCount)
                .set(errorMsg != null, AiDocumentDO::getErrorMsg, errorMsg));
    }

    default List<AiDocumentDO> selectListByKbId(Long kbId) {
        return selectList(new LambdaQueryWrapperX<AiDocumentDO>().eq(AiDocumentDO::getKbId, kbId));
    }

    default List<AiDocumentDO> selectListByKbIds(List<Long> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) return List.of();
        return selectList(new LambdaQueryWrapperX<AiDocumentDO>().in(AiDocumentDO::getKbId, kbIds));
    }

    /**
     * 专利业务标识符走生成列联合索引，避免按知识库加载全部 domain_metadata 后在 JVM 扫描。
     * 对应迁移：migrate-20260825-patent-identifier-index.sql。
     */
    @Select({
            "<script>",
            "SELECT id FROM ai_document",
            "WHERE deleted = 0",
            "AND kb_id IN",
            "<foreach collection='kbIds' item='kbId' open='(' separator=',' close=')'>#{kbId}</foreach>",
            "<if test='applicationNo != null and applicationNo != \"\"'>",
            "AND patent_application_no_norm = #{applicationNo}",
            "</if>",
            "<if test='publicationNo != null and publicationNo != \"\"'>",
            "AND patent_publication_no_norm = #{publicationNo}",
            "</if>",
            "</script>"
    })
    List<Long> selectPatentDocumentIdsByIdentifier(@Param("kbIds") List<Long> kbIds,
                                                   @Param("applicationNo") String applicationNo,
                                                   @Param("publicationNo") String publicationNo);

    /**
     * Query Engine fallback 的 keyset 物理源分页。
     *
     * <p>这里不接收任意字段/SQL，仅暴露 PATENT 数据源的固定白名单。limit 由 service 钳制，
     * 调用方使用 id 游标把所有页读完以后才能形成 completeDataset 结论。</p>
     */
    @Select({
            "<script>",
            "SELECT d.* FROM ai_document d",
            "WHERE d.deleted = 0 AND d.kb_id = #{kbId}",
            "AND d.id &gt; #{afterDocumentId}",
            "AND (d.domain_metadata IS NULL OR TRIM(d.domain_metadata) = ''",
            "     OR JSON_VALID(d.domain_metadata) = 0",
            "     OR JSON_UNQUOTE(JSON_EXTRACT(d.domain_metadata, '$.domainCode')) IS NULL",
            "     OR JSON_UNQUOTE(JSON_EXTRACT(d.domain_metadata, '$.domainCode')) = ''",
            "     OR UPPER(JSON_UNQUOTE(JSON_EXTRACT(d.domain_metadata, '$.domainCode'))) = 'PATENT')",
            "<if test='resolvedEntityIds != null and !resolvedEntityIds.isEmpty()'>",
            "AND d.id IN",
            "<foreach collection='resolvedEntityIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "</if>",
            "<if test='publishedOnly == null or publishedOnly'>",
            "AND EXISTS (SELECT 1 FROM ai_doc_version v",
            "            WHERE v.deleted = 0 AND v.doc_id = d.id AND v.status = 'PUBLISHED'",
            "              AND (v.effective_from IS NULL OR v.effective_from &lt;= NOW())",
            "              AND (v.effective_to IS NULL OR v.effective_to &gt;= NOW()))",
            "</if>",
            "ORDER BY d.id ASC",
            "LIMIT #{limit}",
            "</script>"
    })
    List<AiDocumentDO> selectStructuredPatentDocumentsPage(@Param("kbId") Long kbId,
                                                           @Param("resolvedEntityIds") List<Long> resolvedEntityIds,
                                                           @Param("publishedOnly") Boolean publishedOnly,
                                                           @Param("afterDocumentId") Long afterDocumentId,
                                                           @Param("limit") Integer limit);

    /**
     * 权威 SOURCE_RECORD 计数。聚合在数据库完成，不先加载文档行到 JVM。
     */
    @Select({
            "<script>",
            "SELECT COUNT(DISTINCT d.id) FROM ai_document d",
            "WHERE d.deleted = 0 AND d.kb_id = #{kbId}",
            "<if test='resolvedEntityIds != null and !resolvedEntityIds.isEmpty()'>",
            "AND d.id IN",
            "<foreach collection='resolvedEntityIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "</if>",
            "<if test='publishedOnly == null or publishedOnly'>",
            "AND EXISTS (SELECT 1 FROM ai_doc_version v",
            "            WHERE v.deleted = 0 AND v.doc_id = d.id AND v.status = 'PUBLISHED'",
            "              AND (v.effective_from IS NULL OR v.effective_from &lt;= NOW())",
            "              AND (v.effective_to IS NULL OR v.effective_to &gt;= NOW()))",
            "</if>",
            "</script>"
    })
    Long countStructuredDocuments(@Param("kbId") Long kbId,
                                  @Param("resolvedEntityIds") List<Long> resolvedEntityIds,
                                  @Param("publishedOnly") Boolean publishedOnly);

    /**
     * 权威 PATENT LOGICAL_ENTITY 计数。逻辑身份优先申请号，其次公布号，最后回退物理 document id。
     *
     * <p>注意：源集合条件必须与 selectStructuredPatentDocumentsPage 完全一致。历史专利文档可能没有
     * domainCode 或 domain_metadata 无效；行级 fallback 会把这些文档纳入当前 PATENT 知识库范围，
     * 因此 COUNT pushdown 也必须纳入，不能因执行路径不同产生不同答案。</p>
     */
    @Select({
            "<script>",
            "SELECT COUNT(DISTINCT CASE",
            "  WHEN d.patent_application_no_norm IS NOT NULL AND d.patent_application_no_norm != ''",
            "    THEN CONCAT('APP:', d.patent_application_no_norm)",
            "  WHEN d.patent_publication_no_norm IS NOT NULL AND d.patent_publication_no_norm != ''",
            "    THEN CONCAT('PUB:', d.patent_publication_no_norm)",
            "  ELSE CONCAT('DOC:', d.id) END)",
            "FROM ai_document d",
            "WHERE d.deleted = 0 AND d.kb_id = #{kbId}",
            "AND (d.domain_metadata IS NULL OR TRIM(d.domain_metadata) = ''",
            "     OR JSON_VALID(d.domain_metadata) = 0",
            "     OR JSON_UNQUOTE(JSON_EXTRACT(d.domain_metadata, '$.domainCode')) IS NULL",
            "     OR JSON_UNQUOTE(JSON_EXTRACT(d.domain_metadata, '$.domainCode')) = ''",
            "     OR UPPER(JSON_UNQUOTE(JSON_EXTRACT(d.domain_metadata, '$.domainCode'))) = 'PATENT')",
            "<if test='resolvedEntityIds != null and !resolvedEntityIds.isEmpty()'>",
            "AND d.id IN",
            "<foreach collection='resolvedEntityIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "</if>",
            "<if test='publishedOnly == null or publishedOnly'>",
            "AND EXISTS (SELECT 1 FROM ai_doc_version v",
            "            WHERE v.deleted = 0 AND v.doc_id = d.id AND v.status = 'PUBLISHED'",
            "              AND (v.effective_from IS NULL OR v.effective_from &lt;= NOW())",
            "              AND (v.effective_to IS NULL OR v.effective_to &gt;= NOW()))",
            "</if>",
            "</script>"
    })
    Long countStructuredPatentEntities(@Param("kbId") Long kbId,
                                       @Param("resolvedEntityIds") List<Long> resolvedEntityIds,
                                       @Param("publishedOnly") Boolean publishedOnly);

}
