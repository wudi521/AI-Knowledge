package cn.iocoder.yudao.module.knowledge.dal.mysql.knowledge;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 防止 PATENT 的行级完整源与 SQL pushdown 悄悄使用两套数据范围。
 *
 * <p>这里不验证自然语言；只锁定权威数据源的机器合同。历史数据允许 domain_metadata 缺失/无效，
 * 因此 page fallback 与 PATENT_COUNT 都必须采用相同的 PATENT eligibility 规则。</p>
 */
class PatentStructuredSourceScopeContractTest {

    @Test
    void patentCountAndCompletePageMustShareLegacyCompatibleDomainScope() throws Exception {
        String pageSql = sql(AiDocumentMapper.class.getMethod(
                "selectStructuredPatentDocumentsPage",
                Long.class, List.class, Boolean.class, Long.class, Integer.class));
        String countSql = sql(AiDocumentMapper.class.getMethod(
                "countStructuredPatentEntities",
                Long.class, List.class, Boolean.class));

        for (String fragment : List.of(
                "d.domain_metadata IS NULL",
                "JSON_VALID(d.domain_metadata) = 0",
                "JSON_UNQUOTE(JSON_EXTRACT(d.domain_metadata, '$.domainCode')) IS NULL",
                "JSON_UNQUOTE(JSON_EXTRACT(d.domain_metadata, '$.domainCode')) = ''",
                "UPPER(JSON_UNQUOTE(JSON_EXTRACT(d.domain_metadata, '$.domainCode'))) = 'PATENT'",
                "v.status = 'PUBLISHED'")) {
            assertTrue(pageSql.contains(fragment), "complete page lost source-scope fragment: " + fragment);
            assertTrue(countSql.contains(fragment), "PATENT_COUNT pushdown diverged from complete source: " + fragment);
        }
    }

    private String sql(Method method) {
        Select select = method.getAnnotation(Select.class);
        if (select == null) throw new AssertionError("missing @Select on " + method.getName());
        return String.join(" ", select.value()).replaceAll("\\s+", " ");
    }
}
