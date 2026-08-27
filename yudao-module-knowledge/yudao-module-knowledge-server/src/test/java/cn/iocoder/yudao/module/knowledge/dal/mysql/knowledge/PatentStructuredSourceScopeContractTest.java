package cn.iocoder.yudao.module.knowledge.dal.mysql.knowledge;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 防止 PATENT 的行级完整源与各类 SQL pushdown 悄悄使用两套数据范围。
 *
 * <p>这里不验证自然语言；只锁定权威数据源的机器合同。历史数据允许 domain_metadata 缺失/无效，
 * 因此 complete page、PATENT_COUNT、typed ORDER 都必须采用同一 PATENT eligibility / published scope。</p>
 */
class PatentStructuredSourceScopeContractTest {

    @Test
    void everyPatentPushdownMustShareCompletePageSourceScope() throws Exception {
        String pageSql = sql(AiDocumentMapper.class.getMethod(
                "selectStructuredPatentDocumentsPage",
                Long.class, List.class, Boolean.class, Long.class, Integer.class));
        List<String> pushdownSql = List.of(
                sql(AiDocumentMapper.class.getMethod(
                        "countStructuredPatentEntities", Long.class, List.class, Boolean.class)),
                sql(PatentStructuredOrderMapper.class.getMethod(
                        "selectTitleLengthStats", Long.class, List.class, Boolean.class)),
                sql(PatentStructuredOrderMapper.class.getMethod(
                        "selectTopByTitleLength", Long.class, List.class, Boolean.class, String.class, Integer.class)));

        for (String fragment : List.of(
                "d.domain_metadata IS NULL",
                "JSON_VALID(d.domain_metadata) = 0",
                "JSON_UNQUOTE(JSON_EXTRACT(d.domain_metadata, '$.domainCode')) IS NULL",
                "JSON_UNQUOTE(JSON_EXTRACT(d.domain_metadata, '$.domainCode')) = ''",
                "UPPER(JSON_UNQUOTE(JSON_EXTRACT(d.domain_metadata, '$.domainCode'))) = 'PATENT'",
                "v.status = 'PUBLISHED'")) {
            assertTrue(pageSql.contains(fragment), "complete page lost source-scope fragment: " + fragment);
            for (String sql : pushdownSql) {
                assertTrue(sql.contains(fragment), "PATENT pushdown diverged from complete source: " + fragment);
            }
        }
    }

    @Test
    void orderedRepresentativeMustComeFromPhysicalRowThatActuallyHasTitle() throws Exception {
        String topSql = sql(PatentStructuredOrderMapper.class.getMethod(
                "selectTopByTitleLength", Long.class, List.class, Boolean.class, String.class, Integer.class));

        assertTrue(topSql.contains("MIN(CASE WHEN JSON_VALID(d.domain_metadata) = 1"));
        assertTrue(topSql.contains("NULLIF(TRIM(JSON_UNQUOTE(JSON_EXTRACT(d.domain_metadata, '$.title'))), '') IS NOT NULL"));
        assertTrue(topSql.contains("THEN d.id ELSE NULL END) AS representativeId"));
        assertTrue(topSql.contains("g.representativeId IS NOT NULL"));
    }

    private String sql(Method method) {
        Select select = method.getAnnotation(Select.class);
        if (select == null) throw new AssertionError("missing @Select on " + method.getName());
        return String.join(" ", select.value()).replaceAll("\\s+", " ");
    }
}
