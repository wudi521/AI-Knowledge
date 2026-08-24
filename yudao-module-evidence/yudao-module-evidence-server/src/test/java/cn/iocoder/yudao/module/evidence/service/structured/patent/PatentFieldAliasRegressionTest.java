package cn.iocoder.yudao.module.evidence.service.structured.patent;

import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainEntityRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainFieldRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainMetricRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.FieldDefinition;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0 回归：用户口语“专利号”按 Patent Domain Policy 归一到申请号；
 * “公布号/公开号”继续归一到 PUBLICATION_NO，字段查询不能误判成缺少统计指标。
 */
class PatentFieldAliasRegressionTest {

    @Test
    void patentNumberResolvesToApplicationNumber() {
        DefaultDomainMetricRegistry metricRegistry = new DefaultDomainMetricRegistry();
        DefaultDomainEntityRegistry entityRegistry = new DefaultDomainEntityRegistry();
        DefaultDomainFieldRegistry fieldRegistry = new DefaultDomainFieldRegistry();
        new PatentStructuredPack(metricRegistry, entityRegistry, fieldRegistry);

        FieldDefinition field = fieldRegistry.findByAlias(
                "把4个专利号分别给我一下", PatentStructuredPack.DOMAIN_CODE).orElseThrow();

        assertThat(field.getFieldCode()).isEqualTo(PatentStructuredPack.FIELD_APPLICATION_NO);
    }

    @Test
    void publicationNumberStillResolvesToPublicationNumber() {
        DefaultDomainMetricRegistry metricRegistry = new DefaultDomainMetricRegistry();
        DefaultDomainEntityRegistry entityRegistry = new DefaultDomainEntityRegistry();
        DefaultDomainFieldRegistry fieldRegistry = new DefaultDomainFieldRegistry();
        new PatentStructuredPack(metricRegistry, entityRegistry, fieldRegistry);

        FieldDefinition field = fieldRegistry.findByAlias(
                "这4个专利的公布号分别是什么", PatentStructuredPack.DOMAIN_CODE).orElseThrow();

        assertThat(field.getFieldCode()).isEqualTo(PatentStructuredPack.FIELD_PUBLICATION_NO);
    }
}
