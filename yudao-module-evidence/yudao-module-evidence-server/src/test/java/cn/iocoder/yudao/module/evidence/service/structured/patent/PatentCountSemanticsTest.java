package cn.iocoder.yudao.module.evidence.service.structured.patent;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainEntityRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainFieldRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainMetricRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.Operation;
import cn.iocoder.yudao.module.evidence.service.structured.core.QueryScope;
import cn.iocoder.yudao.module.evidence.service.structured.core.QueryType;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryExecutor;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryPlan;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryResult;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryRespDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryRowDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** “专利数”与“文档数”必须分离：重复导入不能把一件专利算两次。 */
@ExtendWith(MockitoExtension.class)
class PatentCountSemanticsTest {

    @Mock KnowledgeApi knowledgeApi;

    @Test
    void patentCountDedupesDuplicateApplicationNumbers() {
        DefaultDomainMetricRegistry metricRegistry = new DefaultDomainMetricRegistry();
        DefaultDomainEntityRegistry entityRegistry = new DefaultDomainEntityRegistry();
        DefaultDomainFieldRegistry fieldRegistry = new DefaultDomainFieldRegistry();
        new PatentStructuredPack(metricRegistry, entityRegistry, fieldRegistry);
        PatentStructuredDataAdapter adapter = new PatentStructuredDataAdapter(knowledgeApi);
        StructuredQueryExecutor executor = new StructuredQueryExecutor(metricRegistry, List.of(adapter));

        StructuredQueryRespDTO data = new StructuredQueryRespDTO();
        data.setRows(new ArrayList<>(List.of(
                row(65L, "A.pdf", "202311344028.2", "CN 122621758 A", "A"),
                row(66L, "B.pdf", "202311042981.1", "CN 122604134 A", "一种代替印花的运动服"),
                row(67L, "C.pdf", "202311832214.0", "CN 122619519 A", "C"),
                row(68L, "B-copy.pdf", "202311042981.1", "CN 122604134 A", "一种代替印花的运动服")
        )));
        when(knowledgeApi.structuredQuery(any())).thenReturn(CommonResult.success(data));

        StructuredQueryPlan patentCountPlan = StructuredQueryPlan.builder()
                .route("STRUCTURED_QUERY")
                .queryType(QueryType.AGGREGATE)
                .domainCode("PATENT")
                .entityType(PatentStructuredPack.ENTITY_PATENT_DOCUMENT)
                .scope(QueryScope.currentKb(6L))
                .metricCode(PatentStructuredPack.METRIC_PATENT_COUNT)
                .operation(Operation.COUNT)
                .filters(java.util.Map.of("publishedOnly", "true"))
                .build();

        StructuredQueryResult result = executor.execute(patentCountPlan);

        assertThat(result.isUnsupported()).isFalse();
        assertThat(result.getValue()).isEqualTo(3d);
        assertThat(result.getRows()).hasSize(3);
    }

    @Test
    void logicalPatentFieldListAlsoDedupesDuplicateImports() {
        PatentStructuredDataAdapter adapter = new PatentStructuredDataAdapter(knowledgeApi);
        StructuredQueryRespDTO data = new StructuredQueryRespDTO();
        data.setRows(new ArrayList<>(List.of(
                row(66L, "B.pdf", "202311042981.1", "CN 122604134 A", "一种代替印花的运动服"),
                row(68L, "B-copy.pdf", "202311042981.1", "CN 122604134 A", "一种代替印花的运动服"),
                row(67L, "C.pdf", "202311832214.0", "CN 122619519 A", "一种粒子化磁涌装置及其使用方法")
        )));
        when(knowledgeApi.structuredQuery(any())).thenReturn(CommonResult.success(data));

        StructuredQueryPlan titleListPlan = StructuredQueryPlan.builder()
                .route("AGENT_CAPABILITY")
                .queryType(QueryType.LIST)
                .domainCode("PATENT")
                .entityType(PatentStructuredPack.ENTITY_PATENT_DOCUMENT)
                .scope(QueryScope.currentKb(6L))
                .metricCode(PatentStructuredPack.FIELD_TITLE)
                .fieldCode(PatentStructuredPack.FIELD_TITLE)
                .projections(List.of(PatentStructuredPack.FIELD_TITLE))
                .operation(Operation.NONE)
                .filters(java.util.Map.of("publishedOnly", "true"))
                .build();

        StructuredQueryResult result = adapter.execute(titleListPlan);

        assertThat(result.isUnsupported()).isFalse();
        assertThat(result.getRows()).hasSize(2);
        assertThat(result.getRows()).extracting(StructuredQueryResult.Row::getEntityId)
                .containsExactly(66L, 67L);
    }

    @Test
    void patentAndDocumentAliasesAreSeparated() {
        DefaultDomainMetricRegistry metricRegistry = new DefaultDomainMetricRegistry();
        new PatentStructuredPack(metricRegistry, new DefaultDomainEntityRegistry(), new DefaultDomainFieldRegistry());

        assertThat(metricRegistry.findByAlias("PATENT", "几个专利"))
                .get().extracting(m -> m.getMetricCode())
                .isEqualTo(PatentStructuredPack.METRIC_PATENT_COUNT);
        assertThat(metricRegistry.findByAlias("PATENT", "文档数"))
                .get().extracting(m -> m.getMetricCode())
                .isEqualTo(PatentStructuredPack.METRIC_DOCUMENT_COUNT);
    }

    private StructuredQueryRowDTO row(Long id, String name, String app, String pub, String title) {
        StructuredQueryRowDTO row = new StructuredQueryRowDTO();
        row.setDocumentId(id);
        row.setDocumentName(name);
        row.setApplicationNo(app);
        row.setPublicationNo(pub);
        row.setTitle(title);
        row.setValue(1d);
        return row;
    }
}
