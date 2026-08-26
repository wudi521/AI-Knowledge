package cn.iocoder.yudao.framework.common.plugin;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 领域插件选择时共享的最小上下文。
 *
 * <p>这里只保存跨 Pipeline 都能理解的作用域信息，不承载切片、检索、验证的业务输入；
 * 各 Pipeline 必须继续使用自己的强类型 Request/Result，避免演变成 Map 驱动的万能插件接口。</p>
 */
public record DomainPluginContext(Long tenantId,
                                  Long kbId,
                                  String domainCode,
                                  Set<String> capabilities,
                                  Map<String, Object> attributes) {

    public DomainPluginContext {
        domainCode = normalizeDomain(domainCode);
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static DomainPluginContext of(String domainCode) {
        return new DomainPluginContext(null, null, domainCode, Set.of(), Map.of());
    }

    public DomainPluginContext withAttribute(String key, Object value) {
        java.util.LinkedHashMap<String, Object> next = new java.util.LinkedHashMap<>(attributes);
        if (key != null && value != null) next.put(key, value);
        return new DomainPluginContext(tenantId, kbId, domainCode, capabilities, next);
    }

    public static String normalizeDomain(String domainCode) {
        if (domainCode == null || domainCode.isBlank()) return "GENERAL";
        return domainCode.trim().toUpperCase(Locale.ROOT);
    }
}
