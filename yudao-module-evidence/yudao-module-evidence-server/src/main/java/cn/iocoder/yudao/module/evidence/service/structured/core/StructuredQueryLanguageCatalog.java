package cn.iocoder.yudao.module.evidence.service.structured.core;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 运行时 Query IR 语言能力目录；只聚合插件声明，不识别用户意图。 */
@Component
public class StructuredQueryLanguageCatalog {
    private final List<StructuredQueryLanguageCapabilityProvider> providers;

    public StructuredQueryLanguageCatalog(List<StructuredQueryLanguageCapabilityProvider> providers) {
        this.providers = providers == null ? List.of() : List.copyOf(providers);
    }

    public List<StructuredQueryLanguageCapability> capabilities(String domainCode) {
        List<StructuredQueryLanguageCapability> out = new ArrayList<>();
        for (StructuredQueryLanguageCapabilityProvider provider : providers) {
            if (provider == null) continue;
            if (domainCode != null && !domainCode.equalsIgnoreCase(provider.domainCode())) continue;
            List<StructuredQueryLanguageCapability> declared = provider.capabilities();
            if (declared == null) continue;
            for (StructuredQueryLanguageCapability capability : declared) {
                if (capability == null) continue;
                if (domainCode != null && capability.domainCode() != null
                        && !domainCode.equalsIgnoreCase(capability.domainCode())) continue;
                out.add(capability);
            }
        }
        out.sort(Comparator
                .comparing(StructuredQueryLanguageCapability::domainCode, Comparator.nullsLast(String::compareTo))
                .thenComparing(StructuredQueryLanguageCapability::irVersion, Comparator.nullsLast(String::compareTo)));
        return out.stream().distinct().toList();
    }
}
