package cn.iocoder.yudao.module.evidence.service.structured.patent;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainEntityRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainFieldRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainMetricRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.MultiFieldProjectionService;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryExecutor;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeDocumentRespDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryRespDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryRowDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PatentFilterProjectionEndToEndTest {

    @Mock KnowledgeApi knowledgeApi;

    @Test
    void titleContainsFiltersCompleteRowsAndProjectsOnlyApplicationAndPublicationNumber() {
        DefaultDomainMetricRegistry metrics = new DefaultDomainMetricRegistry();
        DefaultDomainEntityRegistry entities = new DefaultDomainEntityRegistry();
        DefaultDomainFieldRegistry fields = new DefaultDomainFieldRegistry();
        new PatentStructuredPack(metrics, entities, fields);
        PatentStructuredDataAdapter adapter = new PatentStructuredDataAdapter(knowledgeApi);
        StructuredQueryExecutor executor = new StructuredQueryExecutor(metrics, List.of(adapter));
        MultiFieldProjectionService service = new MultiFieldProjectionService(fields, metrics, entities, executor);

        StructuredQueryRowDTO magnetic = row(67L, "2023118322140.pdf",
                "202311832214.0", "CN 122619519 A");
        StructuredQueryRowDTO sports = row(66L, "一种代替印花的运动服",
                "202311042981.1", "CN 122604134 A");
        sports.setTitle("一种代替印花的运动服");
        StructuredQueryRespDTO data = new StructuredQueryRespDTO();
        data.setRows(List.of(magnetic, sports));
        data.setTruncated(false);
        when(knowledgeApi.structuredQuery(any())).thenReturn(CommonResult.success(data));
        KnowledgeDocumentRespDTO magneticDocument = new KnowledgeDocumentRespDTO();
        magneticDocument.setId(67L);
        magneticDocument.setName("2023118322140.pdf");
        magneticDocument.setDomainMetadata("{\"domainCode\":\"PATENT\",\"title\":\"一种粒子化磁涌装置及其使用方法\"}");
        when(knowledgeApi.getDocumentMap(any())).thenReturn(CommonResult.success(Map.of(67L, magneticDocument)));

        MultiFieldProjectionService.Result result = service.tryHandle(
                "标题包含磁涌的申请号和公布号", 6L, PatentStructuredPack.DOMAIN_CODE, List.of());

        assertThat(result.state()).isEqualTo(MultiFieldProjectionService.State.ANSWER);
        assertThat(result.plan().getProjections()).containsExactly(
                PatentStructuredPack.FIELD_APPLICATION_NO, PatentStructuredPack.FIELD_PUBLICATION_NO);
        assertThat(result.plan().getFilterExpression()).isNotNull();
        assertThat(result.result().getRows()).hasSize(1);
        assertThat(result.answer()).contains("202311832214.0").contains("CN 122619519 A")
                .doesNotContain("202311042981.1")
                .doesNotContain("标题=");
        assertThat(result.result().getRows().get(0).getFields())
                .containsEntry(PatentStructuredPack.FIELD_TITLE, "一种粒子化磁涌装置及其使用方法");
    }

    @Test
    void missingCanonicalTitleCannotBeReportedAsCompleteNoMatch() {
        DefaultDomainMetricRegistry metrics = new DefaultDomainMetricRegistry();
        DefaultDomainEntityRegistry entities = new DefaultDomainEntityRegistry();
        DefaultDomainFieldRegistry fields = new DefaultDomainFieldRegistry();
        new PatentStructuredPack(metrics, entities, fields);
        PatentStructuredDataAdapter adapter = new PatentStructuredDataAdapter(knowledgeApi);
        StructuredQueryExecutor executor = new StructuredQueryExecutor(metrics, List.of(adapter));
        MultiFieldProjectionService service = new MultiFieldProjectionService(fields, metrics, entities, executor);

        StructuredQueryRespDTO data = new StructuredQueryRespDTO();
        data.setRows(List.of(row(67L, "2023118322140.pdf", "202311832214.0", "CN 122619519 A")));
        when(knowledgeApi.structuredQuery(any())).thenReturn(CommonResult.success(data));
        when(knowledgeApi.getDocumentMap(any())).thenReturn(CommonResult.success(Map.of()));

        MultiFieldProjectionService.Result result = service.tryHandle(
                "标题包含磁涌的申请号和公布号", 6L, PatentStructuredPack.DOMAIN_CODE, List.of());

        assertThat(result.state()).isEqualTo(MultiFieldProjectionService.State.UNANSWERABLE);
        assertThat(result.result()).isNotNull();
        assertThat(result.result().getUnsupportedReason()).contains("专利标题").contains("不完整");
    }

    private StructuredQueryRowDTO row(Long id, String name, String app, String pub) {
        StructuredQueryRowDTO row = new StructuredQueryRowDTO();
        row.setDocumentId(id);
        row.setDocumentName(name);
        row.setApplicationNo(app);
        row.setPublicationNo(pub);
        return row;
    }
}
