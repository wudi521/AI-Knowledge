package cn.iocoder.yudao.module.evidence.service.structured.patent;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainEntityRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainFieldRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainMetricRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.MultiFieldProjectionService;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryExecutor;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryRespDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryRowDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

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

        StructuredQueryRowDTO magnetic = row(67L, "一种粒子化磁涌装置及其使用方法",
                "202311832214.0", "CN 122619519 A");
        StructuredQueryRowDTO sports = row(66L, "一种代替印花的运动服",
                "202311042981.1", "CN 122604134 A");
        StructuredQueryRespDTO data = new StructuredQueryRespDTO();
        data.setRows(List.of(magnetic, sports));
        data.setTruncated(false);
        when(knowledgeApi.structuredQuery(any())).thenReturn(CommonResult.success(data));

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
