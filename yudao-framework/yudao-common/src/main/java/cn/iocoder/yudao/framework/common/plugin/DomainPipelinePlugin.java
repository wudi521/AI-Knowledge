package cn.iocoder.yudao.framework.common.plugin;

import java.util.Set;

/**
 * 所有领域可插拔能力的共同协议。
 *
 * <p>核心 Pipeline 只依赖该协议做发现、排序和领域匹配；新增领域或实现不允许反向修改核心编排。</p>
 */
public interface DomainPipelinePlugin {

    /** 全局稳定插件标识，用于去重、追踪和运营观测。 */
    String pluginId();

    /** 同一优先级下的确定性顺序，数值越小越先执行。 */
    default int order() {
        return 0;
    }

    /** 支持的领域代码；* 表示领域无关的通用插件。 */
    default Set<String> supportedDomains() {
        return Set.of("*");
    }

    /**
     * 允许插件基于能力、知识库配置等进一步收窄适用范围；
     * 默认只做领域匹配，不理解任何业务问题或 intent。
     */
    default boolean supports(DomainPluginContext context) {
        String domain = context == null ? "GENERAL" : context.domainCode();
        Set<String> domains = supportedDomains();
        if (domains == null || domains.isEmpty()) return false;
        for (String candidate : domains) {
            if ("*".equals(candidate) || DomainPluginContext.normalizeDomain(candidate).equals(domain)) return true;
        }
        return false;
    }
}
