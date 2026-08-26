package cn.iocoder.yudao.module.retrieval.service.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DomainQueryPolicyRegistryTest {

    @Test
    void exactPolicyWinsAndUnknownDomainFallsBackToGeneralPlugin() {
        DomainQueryPolicyRegistry registry = new DomainQueryPolicyRegistry(List.of(
                policy("GENERAL"), policy("PATENT")));

        assertEquals("PATENT", registry.get("patent").domainCode());
        assertEquals("GENERAL", registry.get("CONTRACT").domainCode());
    }

    private DomainQueryPolicy policy(String domain) {
        return new DomainQueryPolicy() {
            @Override public String domainCode() { return domain; }
            @Override public String queryAnalysisPrompt() { return null; }
            @Override public boolean enableProductGate() { return false; }
            @Override public boolean enableSlotDetection() { return false; }
        };
    }
}
