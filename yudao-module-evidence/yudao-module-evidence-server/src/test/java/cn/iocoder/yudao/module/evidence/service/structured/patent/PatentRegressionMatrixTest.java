package cn.iocoder.yudao.module.evidence.service.structured.patent;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.evidence.service.structured.core.CompletenessGuard;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainEntityRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainFieldRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainMetricRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredAnswerRenderer;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryContextResolver;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryExecutor;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryPreParser;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryService;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryService.State;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import cn.iocoder.yudao.module.knowledge.api.dto.PatentDocumentLookupReqDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryReqDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryRespDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryRowDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CQ-49 Patent Regression Matrix(0 LLM/0 Vector/0 Rerank 确定性路径)。
 * <p>
 * 覆盖回归脚本 A~G: count / field LIST(公布号/申请号分别) / GROUP(分别多少权利要求) /
 * SUM(总共) / AVG(平均) / MAX(哪个最多); H 无字段/metric → CLARIFY。
 * 多轮"它们/这些"(CQ-04~08)由 chat 侧 ReferenceResolver 解析后以 explicitEntityIds 传入(另测)。
 */
class PatentRegressionMatrixTest {

    private static final String DOMAIN = PatentStructuredPack.DOMAIN_CODE;

    private KnowledgeApi knowledgeApi;
    private StructuredQueryService service;

    @BeforeEach
    void setUp() {
        knowledgeApi = mock(KnowledgeApi.class);
        mockStructuredQuery();
        mockLookup();

        DefaultDomainMetricRegistry metricRegistry = new DefaultDomainMetricRegistry();
        DefaultDomainEntityRegistry entityRegistry = new DefaultDomainEntityRegistry();
        DefaultDomainFieldRegistry fieldRegistry = new DefaultDomainFieldRegistry();
        new PatentStructuredPack(metricRegistry, entityRegistry, fieldRegistry);
        PatentStructuredDataAdapter adapter = new PatentStructuredDataAdapter(knowledgeApi);

        StructuredQueryPreParser preParser = new StructuredQueryPreParser();
        StructuredQueryContextResolver contextResolver = new StructuredQueryContextResolver(List.of(adapter));
        StructuredQueryExecutor executor = new StructuredQueryExecutor(metricRegistry, List.of(adapter));
        StructuredAnswerRenderer renderer = new StructuredAnswerRenderer();
        CompletenessGuard guard = new CompletenessGuard();
        service = new StructuredQueryService(preParser, metricRegistry, entityRegistry, fieldRegistry,
                contextResolver, executor, renderer, guard);
    }

    private void mockStructuredQuery() {
        when(knowledgeApi.structuredQuery(any(StructuredQueryReqDTO.class))).thenAnswer(inv -> {
            StructuredQueryReqDTO req = inv.getArgument(0);
            StructuredQueryRespDTO resp = new StructuredQueryRespDTO();
            List<StructuredQueryRowDTO> rows = new ArrayList<>();
            List<long[]> data = List.of(
                    new long[]{101L, 7}, new long[]{102L, 3}, new long[]{103L, 9});
            for (int i = 0; i < data.size(); i++) {
                StructuredQueryRowDTO r = new StructuredQueryRowDTO();
                r.setDocumentId(data.get(i)[0]);
                r.setDocumentName("专利" + (char) ('A' + i));
                r.setApplicationNo("20231104298" + (i + 1) + ".1");
                r.setPublicationNo("CN 12260413" + (4 + i) + " A");
                if (req.getFieldCode() == null) {
                    switch (req.getMetricCode().toUpperCase()) {
                        case "DOCUMENT_COUNT" -> r.setValue(1d);
                        case "CLAIM_COUNT" -> r.setValue((double) data.get(i)[1]);
                        default -> r.setValue(null);
                    }
                } else {
                    r.setValue(null);
                }
                rows.add(r);
            }
            resp.setRows(rows);
            return CommonResult.success(resp);
        });
    }

    private void mockLookup() {
        when(knowledgeApi.lookupPatentDocuments(any(PatentDocumentLookupReqDTO.class)))
                .thenAnswer(inv -> CommonResult.success(List.of(101L, 102L, 103L)));
    }

    private String handle(String query) {
        return service.handle(query, 6L, DOMAIN, List.of()).answer();
    }

    /** 多轮场景: chat 侧已消解上一轮结果集 ids(CQ-04~10) */
    private String handleIds(String query) {
        return service.handle(query, 6L, DOMAIN, List.of(), List.of(101L, 102L, 103L), null).answer();
    }

    @Test
    void caseA_countPatents() {
        String answer = handle("当前知识库有多少个专利？");
        assertNotNull(answer);
        assertTrue(answer.contains("3"), answer);
    }

    @Test
    void caseB_fieldListPublicationNo() {
        String answer = handleIds("这四个公布号分别是什么？");
        assertNotNull(answer);
        assertTrue(answer.contains("CN 122604134"), answer);
        assertTrue(answer.contains("CN 122604136"), answer);
    }

    @Test
    void caseC_fieldListApplicationNo() {
        String answer = service.handle("它们申请号呢？", 6L, DOMAIN, List.of(),
                List.of(101L, 102L, 103L), PatentStructuredPack.FIELD_APPLICATION_NO).answer();
        assertNotNull(answer);
        assertTrue(answer.contains("202311042981.1"), answer);
        assertTrue(answer.contains("202311042983.1"), answer);
    }

    @Test
    void caseD_groupClaimCount() {
        String answer = handleIds("它们分别多少项权利要求？");
        assertNotNull(answer);
        assertTrue(answer.contains("7") && answer.contains("3") && answer.contains("9"), answer);
    }

    @Test
    void caseE_sumClaimCount() {
        String answer = handleIds("总共有多少项权利要求？");
        assertNotNull(answer);
        assertTrue(answer.contains("19"), answer); // 7+3+9
    }

    @Test
    void caseF_avgClaimCount() {
        String full = handleIds("平均有多少项权利要求？");
        assertNotNull(full);
        assertTrue(full.contains("6"), full); // (7+3+9)/3 = 6.33 → "6"
    }

    @Test
    void caseG_maxClaimCount() {
        String answer = handleIds("权利要求最多的专利是哪个？");
        assertNotNull(answer);
        assertTrue(answer.contains("9") || answer.contains("专利C"), answer);
    }

    @Test
    void caseH_semanticMetricClarifies() {
        // "技术方案" 无 metric 也无 field → CLARIFY(禁止猜)
        StructuredQueryService.HandleResult r = service.handle("它们的技术方案分别是什么？", 6L, DOMAIN, List.of(),
                List.of(101L, 102L, 103L), null);
        assertEquals(State.CLARIFY, r.state());
    }

}
