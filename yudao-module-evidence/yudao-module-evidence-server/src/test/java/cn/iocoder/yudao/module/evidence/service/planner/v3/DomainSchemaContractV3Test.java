package cn.iocoder.yudao.module.evidence.service.planner.v3;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainEntityRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainFieldRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainMetricRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.FieldDefinition;
import cn.iocoder.yudao.module.evidence.service.structured.core.FilterOperator;
import cn.iocoder.yudao.module.evidence.service.structured.patent.PatentStructuredPack;
import cn.iocoder.yudao.module.evidence.service.structured.product.ProductStructuredPack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** 发布门禁：领域 Schema 的有限能力必须完整且可编译。 */
class DomainSchemaContractV3Test {

    @Test
    void everyFilterableFieldDeclaresFiniteOperatorsAndValidIdentifierPatterns() {
        DefaultDomainFieldRegistry fields = new DefaultDomainFieldRegistry();
        DefaultDomainMetricRegistry metrics = new DefaultDomainMetricRegistry();
        DefaultDomainEntityRegistry entities = new DefaultDomainEntityRegistry();
        new PatentStructuredPack(metrics, entities, fields);
        new ProductStructuredPack(metrics, entities, fields);

        for (String domain : List.of("PATENT", "PRODUCT")) {
            assertThat(fields.all(domain)).isNotEmpty();
            for (FieldDefinition field : fields.all(domain)) {
                if (field.isFilterable()) assertThat(field.getAllowedOperators())
                        .as("%s.%s allowed operators", domain, field.getFieldCode()).isNotEmpty();
                if (field.getIdentifierPatterns() != null) {
                    for (String regex : field.getIdentifierPatterns()) {
                        assertThat(StrUtil.isNotBlank(regex)).isTrue();
                        assertThat(Pattern.compile(regex)).isNotNull();
                    }
                }
            }
        }
    }

    @Test
    void canonicalExternalOperatorsRoundTripToTypedEnum() {
        for (FilterOperator operator : FilterOperator.values()) {
            assertThat(FilterOperator.fromExternal(operator.name())).contains(operator);
        }
    }
}
