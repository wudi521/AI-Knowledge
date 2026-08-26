package cn.iocoder.yudao.framework.common.plugin;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void requiredExecutionPipelineCanFailFastWhenWildcardFallbackIsMissing() {
        DomainPluginResolver<TestPlugin> resolver = new DomainPluginResolver<>(List.of(
                new TestPlugin("patent-only", 0, Set.of("PATENT"))));

        assertFalse(resolver.hasWildcardFallback());
        assertThrows(IllegalStateException.class,
                () -> resolver.requireWildcardFallback("test execution"));
    }

    @Test
    void wildcardFallbackIsDetectedWithoutChangingNormalResolution() {
        DomainPluginResolver<TestPlugin> resolver = new DomainPluginResolver<>(List.of(
                new TestPlugin("generic", 0, Set.of("*")),
                new TestPlugin("patent", 0, Set.of("PATENT"))));

        assertTrue(resolver.hasWildcardFallback());
        resolver.requireWildcardFallback("test execution");
        assertEquals("generic", resolver.requireFirst(DomainPluginContext.of("CONTRACT"), "test").pluginId());
    }

    private record TestPlugin(String pluginId, int order, Set<String> supportedDomains)
            implements DomainPipelinePlugin {
    }
}
