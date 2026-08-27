package cn.iocoder.yudao.module.knowledge.dal.mysql.knowledge;

import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.PatentStructuredNumericAggregateStatsDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * PATENT 数值指标的权威 SQL 聚合。
 *
 * <p>当前只承载 claimCount。逻辑实体身份、PATENT eligibility、published scope 与完整分页 fallback
 * 使用同一合同；SQL 不接收动态列名、函数或表达式。</p>
 */
@Mapper
public interface PatentStructuredNumericAggregateMapper {

    @Select({
            "<script>",
            "SELECT COUNT(*) AS sourceEntityCount,",
            "       COALESCE(SUM(CASE WHEN g.claimValue IS NULL THEN 1 ELSE 0 END), 0) AS missingValueCount,",
            "       COALESCE(SUM(CASE WHEN g.claimVariants &gt; 1 THEN 1 ELSE 0 END), 0) AS conflictCount,",
            "       COALESCE(SUM(g.claimValue), 0) AS sumValue,",
            "       COALESCE(AVG(g.claimValue), 0) AS avgValue,",
            "       COALESCE(MIN(g.claimValue), 0) AS minValue,",
            "       COALESCE(MAX(g.claimValue), 0) AS maxValue",
            "FROM (",
            "  SELECT CASE",
            "           WHEN d.patent_application_no_norm IS NOT NULL AND d.patent_application_no_norm != ''",
            "             THEN CONCAT('APP:', d.patent_application_no_norm)",
            "           WHEN d.patent_publication_no_norm IS NOT NULL AND d.patent_publication_no_norm != ''",
            "             THEN CONCAT('PUB:', d.patent_publication_no_norm)",
            "           ELSE CONCAT('DOC:', d.id) END AS logicalKey,",
            "         MAX(CASE WHEN JSON_VALID(d.domain_metadata) = 1",
            "                   AND JSON_UNQUOTE(JSON_EXTRACT(d.domain_metadata, '$.claimCount')) REGEXP '^[0-9]+$'",
            "                  THEN CAST(JSON_UNQUOTE(JSON_EXTRACT(d.domain_metadata, '$.claimCount')) AS DECIMAL(20,4))",
            "                  ELSE NULL END) AS claimValue,",
            "         COUNT(DISTINCT CASE WHEN JSON_VALID(d.domain_metadata) = 1",
            "                   AND JSON_UNQUOTE(JSON_EXTRACT(d.domain_metadata, '$.claimCount')) REGEXP '^[0-9]+$'",
            "                  THEN CAST(JSON_UNQUOTE(JSON_EXTRACT(d.domain_metadata, '$.claimCount')) AS DECIMAL(20,4))",
            "                  ELSE NULL END) AS claimVariants",
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
    PatentStructuredNumericAggregateStatsDO selectClaimCountStats(@Param("kbId") Long kbId,
                                                                  @Param("resolvedEntityIds") List<Long> resolvedEntityIds,
                                                                  @Param("publishedOnly") Boolean publishedOnly);
}
