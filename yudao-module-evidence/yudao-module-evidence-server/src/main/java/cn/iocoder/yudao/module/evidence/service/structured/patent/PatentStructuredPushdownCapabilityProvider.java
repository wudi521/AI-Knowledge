package cn.iocoder.yudao.module.evidence.service.structured.patent;

import cn.iocoder.yudao.module.evidence.service.structured.core.Operation;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredPushdownCapability;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredPushdownCapabilityProvider;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredValueTransform;
import org.springframework.stereotype.Component;

import java.util.List;

/** PATENT 当前已经有权威完整性证明的 typed 下推能力目录。 */
@Component
public class PatentStructuredPushdownCapabilityProvider implements StructuredPushdownCapabilityProvider {

    @Override
    public String domainCode() {
        return PatentStructuredPack.DOMAIN_CODE;
    }

    @Override
    public List<StructuredPushdownCapability> capabilities() {
        return List.of(
                new StructuredPushdownCapability(
                        PatentStructuredPack.DOMAIN_CODE, Operation.COUNT.name(), null,
                        PatentStructuredPack.METRIC_DOCUMENT_COUNT, List.of(), false, false,
                        "SCALAR", "KNOWLEDGE_SQL"),
                new StructuredPushdownCapability(
                        PatentStructuredPack.DOMAIN_CODE, Operation.COUNT.name(), null,
                        PatentStructuredPack.METRIC_PATENT_COUNT, List.of(), false, false,
                        "SCALAR", "KNOWLEDGE_SQL"),
                new StructuredPushdownCapability(
                        PatentStructuredPack.DOMAIN_CODE, Operation.MIN.name(), PatentStructuredPack.FIELD_TITLE,
                        null, List.of(StructuredValueTransform.LENGTH.name()), false, false,
                        "SCALAR", "KNOWLEDGE_SQL"),
                new StructuredPushdownCapability(
                        PatentStructuredPack.DOMAIN_CODE, Operation.MAX.name(), PatentStructuredPack.FIELD_TITLE,
                        null, List.of(StructuredValueTransform.LENGTH.name()), false, false,
                        "SCALAR", "KNOWLEDGE_SQL"),
                new StructuredPushdownCapability(
                        PatentStructuredPack.DOMAIN_CODE, "ORDER_TOP_N", PatentStructuredPack.FIELD_TITLE,
                        null, List.of(StructuredValueTransform.LENGTH.name()), false, true,
                        "ROWS", "KNOWLEDGE_SQL")
        );
    }
}
