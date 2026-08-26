package cn.iocoder.yudao.module.evidence.service.structured.core;

import cn.iocoder.yudao.module.evidence.service.structured.patent.PatentStructuredPack;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetricDataGrainContractTest {

    @Test
    void patentMetricsMustDeclarePhysicalVsLogicalGrain() {
        DefaultDomainMetricRegistry metrics = new DefaultDomainMetricRegistry();
        DefaultDomainEntityRegistry entities = new DefaultDomainEntityRegistry();
        DefaultDomainFieldRegistry fields = new DefaultDomainFieldRegistry();
        new PatentStructuredPack(metrics, entities, fields);

        assertThat(metrics.lookup(PatentStructuredPack.DOMAIN_CODE,
                PatentStructuredPack.METRIC_DOCUMENT_COUNT).orElseThrow().getDataGrain())
                .isEqualTo(DataGrain.SOURCE_RECORD);
        assertThat(metrics.lookup(PatentStructuredPack.DOMAIN_CODE,
                PatentStructuredPack.METRIC_PATENT_COUNT).orElseThrow().getDataGrain())
                .isEqualTo(DataGrain.LOGICAL_ENTITY);
        assertThat(metrics.lookup(PatentStructuredPack.DOMAIN_CODE,
                PatentStructuredPack.METRIC_CLAIM_COUNT).orElseThrow().getDataGrain())
                .isEqualTo(DataGrain.LOGICAL_ENTITY);
    }
}
