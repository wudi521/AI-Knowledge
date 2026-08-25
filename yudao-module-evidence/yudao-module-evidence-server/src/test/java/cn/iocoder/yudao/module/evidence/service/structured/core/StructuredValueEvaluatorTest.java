package cn.iocoder.yudao.module.evidence.service.structured.core;

import cn.iocoder.yudao.module.evidence.service.structured.patent.PatentStructuredPack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredValueEvaluatorTest {

    private StructuredValueEvaluator evaluator;

    @BeforeEach
    void setUp() {
        DefaultDomainFieldRegistry fields = new DefaultDomainFieldRegistry();
        new PatentStructuredPack(new DefaultDomainMetricRegistry(), new DefaultDomainEntityRegistry(), fields);
        evaluator = new StructuredValueEvaluator(fields);
    }

    @Test
    void invalidDeclaredDateMustBecomeMissingInsteadOfStringComparableValue() {
        StructuredQueryResult.Row row = row("FILING_DATE", "2024-99-88");
        StructuredValueExpression expression = new StructuredValueExpression("FILING_DATE", false, List.of());

        assertThat(evaluator.values("PATENT", row, expression)).isEmpty();
        assertThat(evaluator.literalsValid("DATE", List.of("2024-99-88"))).isFalse();
        assertThat(evaluator.literalsValid("DATE", List.of("2024-05-01"))).isTrue();
    }

    @Test
    void multiValueFieldOnlyExplodesWhenExplicitlyRequested() {
        StructuredQueryResult.Row row = row("INVENTOR", "李四,张三");

        List<String> collectionValue = evaluator.values("PATENT", row,
                new StructuredValueExpression("INVENTOR", false, List.of()));
        List<String> exploded = evaluator.values("PATENT", row,
                new StructuredValueExpression("INVENTOR", true, List.of()));

        assertThat(collectionValue).hasSize(1);
        assertThat(collectionValue.get(0)).contains("李四").contains("张三");
        assertThat(exploded).containsExactly("李四", "张三");
    }

    @Test
    void multiValueSplitAndSurnameTransformAreDeterministicAcrossCommonSeparators() {
        StructuredQueryResult.Row row = row("INVENTOR", "张三，欧阳明, John Smith；李四");
        StructuredValueExpression expression = new StructuredValueExpression(
                "INVENTOR", true, List.of(StructuredValueTransform.PERSON_SURNAME));

        assertThat(evaluator.values("PATENT", row, expression))
                .containsExactly("张", "欧阳", "Smith", "李");
    }

    @Test
    void oneFailedElementMustInvalidateWholeMultiValueDerivedResult() {
        StructuredQueryResult.Row row = row("INVENTOR", "张三、X");
        StructuredValueExpression expression = new StructuredValueExpression(
                "INVENTOR", true, List.of(StructuredValueTransform.PERSON_SURNAME));

        assertThat(evaluator.values("PATENT", row, expression)).isEmpty();
    }

    @Test
    void nonCountTransformOnMultiValueFieldRequiresExplode() {
        StructuredValueExpression invalid = new StructuredValueExpression(
                "INVENTOR", false, List.of(StructuredValueTransform.PERSON_SURNAME));
        StructuredValueExpression count = new StructuredValueExpression(
                "INVENTOR", false, List.of(StructuredValueTransform.VALUE_COUNT));

        assertThat(evaluator.validate("PATENT", invalid).valid()).isFalse();
        assertThat(evaluator.validate("PATENT", invalid).message()).contains("requires explode=true");
        assertThat(evaluator.validate("PATENT", count).valid()).isTrue();
    }

    @Test
    void yearMonthAndLengthTransformsUseDeclaredTypes() {
        StructuredQueryResult.Row dateRow = row("FILING_DATE", "2024/07/09");
        StructuredQueryResult.Row titleRow = row("TITLE", "一种ABC装置");

        assertThat(evaluator.values("PATENT", dateRow,
                new StructuredValueExpression("FILING_DATE", false,
                        List.of(StructuredValueTransform.YEAR_MONTH))))
                .containsExactly("2024-07");
        assertThat(evaluator.values("PATENT", titleRow,
                new StructuredValueExpression("TITLE", false,
                        List.of(StructuredValueTransform.LENGTH))))
                .containsExactly(String.valueOf("一种ABC装置".codePointCount(0, "一种ABC装置".length())));
    }

    private StructuredQueryResult.Row row(String field, String value) {
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put(field, value);
        return StructuredQueryResult.Row.builder()
                .entityId(1L)
                .entityName("测试对象")
                .fields(fields)
                .build();
    }
}
