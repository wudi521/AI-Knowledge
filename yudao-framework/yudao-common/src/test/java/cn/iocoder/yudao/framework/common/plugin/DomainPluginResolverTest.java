package cn.iocoder.yudao.framework.common.plugin;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DomainPluginResolverTest {

    @Test
    void exactDomainMustWinOverGenericFallback() {
        DomainPluginResolver<TestPlugin> resolver = new DomainPluginResolver<>(List.of(
                new TestPlugin("generic", 0, Set.of("*")),
                new TestPlugin("patent", 100, Set.of("PATENT"))));

        assertEquals(List.of("patent", "generic"), resolver.resolve(DomainPluginContext.of("patent"))
                .stream().map(TestPlugin::pluginId).toList());
    }

    @Test
    void resolverMustBeDeterministicAndFilterUnsupportedDomains() {
        DomainPluginResolver<TestPlugin> resolver = new DomainPluginResolver<>(List.of(
                new TestPlugin("z", 20, Set.of("*")),
                new TestPlugin("a", 10, Set.of("*")),
                new TestPlugin("contract", 0, Set.of("CONTRACT"))));

        assertEquals(List.of("a", "z"), resolver.resolve(DomainPluginContext.of("PATENT"))
                .stream().map(TestPlugin::pluginId).toList());
    }

    @Test
    void duplicatePluginIdsMustFailFast() {
        assertThrows(IllegalStateException.class, () -> new DomainPluginResolver<>(List.of(
                new TestPlugin("same", 0, Set.of("*")),
                new TestPlugin("same", 1, Set.of("PATENT")))));
    }

    private record TestPlugin(String pluginId, int order, Set<String> supportedDomains)
            implements DomainPipelinePlugin {
    }
}
