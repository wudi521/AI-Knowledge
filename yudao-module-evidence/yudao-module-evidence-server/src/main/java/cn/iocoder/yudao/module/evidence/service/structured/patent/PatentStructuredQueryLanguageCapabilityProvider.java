package cn.iocoder.yudao.module.evidence.service.structured.patent;

import cn.iocoder.yudao.module.evidence.service.structured.core.FilterOperator;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredPipelinePlan;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryLanguageCapability;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryLanguageCapabilityProvider;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * PATENT 插件声明的 Query IR 语言边界。
 *
 * <p>这里故意不出现 TITLE、PATENT_COUNT、ORDER_TOP_N 等“字段 + 运算”的组合签名；
 * 字段/指标来自 Domain Schema，Planner 通过下面这些通用语言原语自由组合计划。</p>
 */
@Component
public class PatentStructuredQueryLanguageCapabilityProvider implements StructuredQueryLanguageCapabilityProvider {

    @Override
    public String domainCode() {
        return PatentStructuredPack.DOMAIN_CODE;
    }

    @Override
    public List<StructuredQueryLanguageCapability> capabilities() {
        return List.of(new StructuredQueryLanguageCapability(
                PatentStructuredPack.DOMAIN_CODE,
                StructuredPipelinePlan.IR_VERSION,
                List.of("SELECT", "FILTER", "GROUP_BY", "AGGREGATE", "HAVING", "ORDER_BY", "DISTINCT", "LIMIT"),
                List.of("FIELD", "TRANSFORMED_FIELD", "MULTI_VALUE_EXPLODE", "AGGREGATE_VALUE"),
                List.of("COUNT", "COUNT_DISTINCT", "SUM", "AVG", "MIN", "MAX"),
                Arrays.stream(FilterOperator.values()).map(Enum::name).toList(),
                List.of("SCALAR", "ROWS", "GROUPS"),
                List.of("CANONICAL_COMPLETE_DATASET", "OPTIONAL_TYPED_PUSHDOWN")
        ));
    }
}
