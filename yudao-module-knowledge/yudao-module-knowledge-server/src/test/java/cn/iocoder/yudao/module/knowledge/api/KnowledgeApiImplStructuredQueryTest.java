package cn.iocoder.yudao.module.knowledge.api;

import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryReqDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryRespDTO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.AiDocumentDO;
import cn.iocoder.yudao.module.knowledge.dal.mysql.knowledge.AiDocumentMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeApiImplStructuredQueryTest {

    @InjectMocks KnowledgeApiImpl api;
    @Mock AiDocumentMapper aiDocumentMapper;

    @Test
    void returnsCanonicalPatentFieldsSeparatelyFromUploadedFileName() {
        AiDocumentDO document = new AiDocumentDO();
        document.setId(67L);
        document.setKbId(6L);
        document.setName("2023118322140.pdf");
        document.setDomainMetadata("""
                {"domainCode":"PATENT","title":"一种粒子化磁涌装置及其使用方法",
                 "applicationNo":"202311832214.0","publicationNo":"CN 122619519 A",
                 "applicants":["魏民"],"inventors":["魏民","张三"],
                 "filingDate":"2023-12-27","publicationDate":"2025-08-19"}
                """);
        when(aiDocumentMapper.selectListByKbId(6L)).thenReturn(List.of(document));

        StructuredQueryReqDTO req = new StructuredQueryReqDTO();
        req.setKbId(6L);
        req.setMetricCode("TITLE");
        req.setPublishedOnly(false);

        StructuredQueryRespDTO result = api.structuredQuery(req).getCheckedData();

        assertThat(result.getRows()).singleElement().satisfies(row -> {
            assertThat(row.getDocumentName()).isEqualTo("2023118322140.pdf");
            assertThat(row.getTitle()).isEqualTo("一种粒子化磁涌装置及其使用方法");
            assertThat(row.getApplicationNo()).isEqualTo("202311832214.0");
            assertThat(row.getPublicationNo()).isEqualTo("CN 122619519 A");
            assertThat(row.getApplicant()).isEqualTo("魏民");
            assertThat(row.getInventor()).isEqualTo("魏民、张三");
            assertThat(row.getFilingDate()).isEqualTo("2023-12-27");
            assertThat(row.getPublicationDate()).isEqualTo("2025-08-19");
        });
    }

    @Test
    void dataSourceFailureCannotBeReportedAsSuccessfulEmptySet() {
        when(aiDocumentMapper.selectListByKbId(6L)).thenThrow(new IllegalStateException("database unavailable"));
        StructuredQueryReqDTO req = new StructuredQueryReqDTO();
        req.setKbId(6L);
        req.setMetricCode("TITLE");

        assertThatThrownBy(() -> api.structuredQuery(req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("结构化数据读取失败");
    }
}
