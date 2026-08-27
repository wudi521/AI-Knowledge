package cn.iocoder.yudao.module.knowledge.dal.mysql.knowledge;

import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.PatentStructuredOrderStatsDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * PATENT typed order 的权威 SQL 下推。
 *
 * <p>当前只开放 TITLE + LENGTH。这里枚举的是后端已经证明等价的数据运算能力，不是自然语言问法。
 * SQL 表达式完全由服务端固定，调用方只能传 scope、方向和 limit。</p>
 */
@Mapper
public interface PatentStructuredOrderMapper {

    /**
     * 对完整逻辑专利集合做 TITLE 完整性/冲突统计。
     * 逻辑身份和 PATENT source eligibility 必须与完整分页 fallback 保持一致。
     */
    @Select({
            "<script>",
            "SELECT COUNT(*) AS sourceEntityCount,",
            "       COALESCE(SUM(CASE WHEN g.titleValue IS NULL THEN 1 ELSE 0 END), 0) AS missingValueCount,",
            "       COALESCE(SUM(CASE WHEN g.titleVariants &gt; 1 THEN 1 ELSE 0 END), 0) AS conflictCount",
            "FROM (",
            "  SELECT CASE",
            "           WHEN d.patent_application_no_norm IS NOT NULL AND d.patent_application_no_norm != ''",
            "             THEN CONCAT('APP:', d.patent_application_no_norm)",
            "           WHEN d.patent_publication_no_norm IS NOT NULL AND d.patent_publication_no_norm != ''",
            "             THEN CONCAT('PUB:', d.patent_publication_no_norm)",
            "           ELSE CONCAT('DOC:', d.id) END AS logicalKey,",
            "         MAX(CASE WHEN JSON_VALID(d.domain_metadata) = 1",
            "              THEN NULLIF(TRIM(JSON_UNQUOTE(JSON_EXTRACT(d.domain_metadata, '$.title'))), '')",
            "              ELSE NULL END) AS titleValue,",
            "         COUNT(DISTINCT CASE WHEN JSON_VALID(d.domain_metadata) = 1",
            "              THEN NULLIF(TRIM(JSON_UNQUOTE(JSON_EXTRACT(d.domain_metadata, '$.title'))), '')",
            "              ELSE NULL END) AS titleVariants",
            "  FROM ai_document d",
            "  WHERE d.deleted = 0 AND d.kb_id = #{kbId}",
            "  AND (d.domain_metadata IS NULL OR TRIM(d.domain_metadata) = ''",
            "       OR JSON_VALID(d.domain_metadata) = 0",
            "       OR JSON_UNQUOTE(JSON_EXTRACT(d.domain_metadata, '$.domainCode')) IS NULL",
            "       OR JSON_UNQUOTE(JSON_EXTRACT(d.domain_metadata, '$.domainCode')) = ''",
            "       OR UPPER(JSON_UNQUOTE(JSON_EXTRACT(d.domain_metadata, '$.domainCode'))) = 'PATENT')",
            "  <if test='resolvedEntityIds != null and !resolvedEntityIds.isEmpty()'>",
            "  AND d.id IN",
            "  <foreach collection='resolvedEntityIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "  </if>",
            "  <if test='publishedOnly == null or publishedOnly'>",
            "  AND EXISTS (SELECT 1 FROM ai_doc_version v",
            "              WHERE v.deleted = 0 AND v.doc_id = d.id AND v.status = 'PUBLISHED'",
            "                AND (v.effective_from IS NULL OR v.effective_from &lt;= NOW())",
            "                AND (v.effective_to IS NULL OR v.effective_to &gt;= NOW()))",
            "  </if>",
            "  GROUP BY CASE",
            "           WHEN d.patent_application_no_norm IS NOT NULL AND d.patent_application_no_norm != ''",
            "             THEN CONCAT('APP:', d.patent_application_no_norm)",
            "           WHEN d.patent_publication_no_norm IS NOT NULL AND d.patent_publication_no_norm != ''",
            "             THEN CONCAT('PUB:', d.patent_publication_no_norm)",
            "           ELSE CONCAT('DOC:', d.id) END",
            ") g",
            "</script>"
    })
    PatentStructuredOrderStatsDO selectTitleLengthStats(@Param("kbId") Long kbId,
                                                        @Param("resolvedEntityIds") List<Long> resolvedEntityIds,
                                                        @Param("publishedOnly") Boolean publishedOnly);

    /**
     * 在数据库对完整逻辑实体集合计算 CHAR_LENGTH(title)，只把最终 Top-N 代表 documentId 返回给 Evidence。
     * 相同长度按最小 documentId 稳定排序，与 keyset fallback 的稳定源顺序保持一致。
     */
    @Select({
            "<script>",
            "SELECT g.representativeId",
            "FROM (",
            "  SELECT CASE",
            "           WHEN d.patent_application_no_norm IS NOT NULL AND d.patent_application_no_norm != ''",
            "             THEN CONCAT('APP:', d.patent_application_no_norm)",
            "           WHEN d.patent_publication_no_norm IS NOT NULL AND d.patent_publication_no_norm != ''",
            "             THEN CONCAT('PUB:', d.patent_publication_no_norm)",
            "           ELSE CONCAT('DOC:', d.id) END AS logicalKey,",
            "         MIN(d.id) AS representativeId,",
            "         MAX(CASE WHEN JSON_VALID(d.domain_metadata) = 1",
            "              THEN NULLIF(TRIM(JSON_UNQUOTE(JSON_EXTRACT(d.domain_metadata, '$.title'))), '')",
            "              ELSE NULL END) AS titleValue",
            "  FROM ai_document d",
            "  WHERE d.deleted = 0 AND d.kb_id = #{kbId}",
            "  AND (d.domain_metadata IS NULL OR TRIM(d.domain_metadata) = ''",
            "       OR JSON_VALID(d.domain_metadata) = 0",
            "       OR JSON_UNQUOTE(JSON_EXTRACT(d.domain_metadata, '$.domainCode')) IS NULL",
            "       OR JSON_UNQUOTE(JSON_EXTRACT(d.domain_metadata, '$.domainCode')) = ''",
            "       OR UPPER(JSON_UNQUOTE(JSON_EXTRACT(d.domain_metadata, '$.domainCode'))) = 'PATENT')",
            "  <if test='resolvedEntityIds != null and !resolvedEntityIds.isEmpty()'>",
            "  AND d.id IN",
            "  <foreach collection='resolvedEntityIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "  </if>",
            "  <if test='publishedOnly == null or publishedOnly'>",
            "  AND EXISTS (SELECT 1 FROM ai_doc_version v",
            "              WHERE v.deleted = 0 AND v.doc_id = d.id AND v.status = 'PUBLISHED'",
            "                AND (v.effective_from IS NULL OR v.effective_from &lt;= NOW())",
            "                AND (v.effective_to IS NULL OR v.effective_to &gt;= NOW()))",
            "  </if>",
            "  GROUP BY CASE",
            "           WHEN d.patent_application_no_norm IS NOT NULL AND d.patent_application_no_norm != ''",
            "             THEN CONCAT('APP:', d.patent_application_no_norm)",
            "           WHEN d.patent_publication_no_norm IS NOT NULL AND d.patent_publication_no_norm != ''",
            "             THEN CONCAT('PUB:', d.patent_publication_no_norm)",
            "           ELSE CONCAT('DOC:', d.id) END",
            ") g",
            "WHERE g.titleValue IS NOT NULL",
            "<choose>",
            "  <when test='direction == \"ASC\"'>ORDER BY CHAR_LENGTH(g.titleValue) ASC, g.representativeId ASC</when>",
            "  <otherwise>ORDER BY CHAR_LENGTH(g.titleValue) DESC, g.representativeId ASC</otherwise>",
            "</choose>",
            "LIMIT #{limit}",
            "</script>"
    })
    List<Long> selectTopByTitleLength(@Param("kbId") Long kbId,
                                      @Param("resolvedEntityIds") List<Long> resolvedEntityIds,
                                      @Param("publishedOnly") Boolean publishedOnly,
                                      @Param("direction") String direction,
                                      @Param("limit") Integer limit);
}
