package cn.iocoder.yudao.module.evidence.service.structured.patent;

import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredPushdownCapability;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredPushdownCapabilityProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 迁移期保留的旧“字段 + 运算 + transform 组合签名”Provider。
 *
 * <p>这类组合目录不再作为 Planner 的认知模型：真实下推仍由 StructuredPushdownAdapter.supports(plan)
 * 对完整 Query IR 做最终判断；Planner 使用 StructuredQueryLanguageCapabilityProvider 发现通用语言边界。
 * 因此这里故意不再枚举 COUNT/TITLE+LENGTH/TOP_N 等组合。</p>
 */
@Component
@Deprecated
public class PatentStructuredPushdownCapabilityProvider implements StructuredPushdownCapabilityProvider {

    @Override
    public String domainCode() {
        return PatentStructuredPack.DOMAIN_CODE;
    }

    @Override
    public List<StructuredPushdownCapability> capabilities() {
        return List.of();
    }
}
