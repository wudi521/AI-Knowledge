package cn.iocoder.yudao.module.evidence.service.structured.patent;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.evidence.api.dto.ChatTurnDTO;
import cn.iocoder.yudao.module.evidence.service.structured.core.CompletenessGuard;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainEntityRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainFieldRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainMetricRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DomainMetricRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.MetricDefinition;
import cn.iocoder.yudao.module.evidence.service.structured.core.Operation;
import cn.iocoder.yudao.module.evidence.service.structured.core.QueryScopeType;
import cn.iocoder.yudao.module.evidence.service.structured.core.QueryType;
import cn.iocoder.yudao.module.evidence.service.structured.core.SortDirection;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredAnswerRenderer;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryContextResolver;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryExecutor;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryPreParser;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryService;
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
 * Patent Domain Pack 验收测试(当前 KB 三件专利 claimCount = 7/3/9)。
 * <p>
 * 覆盖测试矩阵 A-G + Clarification(H) + 指标注册/同义词/不支持指标拒绝。
 * 所有答案必须确定性生成: 0 LLM / 0 Vector / 0 Rerank / 0 Generate / 0 Verify。
 */
class PatentStructuredQueryTest {

    private static final String DOMAIN = PatentStructuredPack.DOMAIN_CODE;

    private KnowledgeApi knowledgeApi;
    private StructuredQueryService service;

    /** 当前 KB 三件专利(claimCount 7/3/9) */
    record Patent(Long docId, String name, String appNo, String pubNo, int claimCount) {
    }

    private final List<Patent> patents = List.of(
            new Patent(101L, "专利A", "202311042981.1", "CN 122604134 A", 7),
            new Patent(102L, "专利B", "202311042982.2", "CN 122604135 A", 3),
            new Patent(103L, "专利C", "202311042983.3", "CN 122604136 A", 9));

    @BeforeEach
    void setUp() {
        knowledgeApi = mock(KnowledgeApi.class);
        mockStructuredQuery();
        mockLookupPatentDocuments();

        DefaultDomainMetricRegistry metricRegistry = new DefaultDomainMetricRegistry();
        DefaultDomainEntityRegistry entityRegistry = new DefaultDomainEntityRegistry();
        DefaultDomainFieldRegistry fieldRegistry = new DefaultDomainFieldRegistry();
        new PatentStructuredPack(metricRegistry, entityRegistry, fieldRegistry); // 注册 Patent Domain Pack
        PatentStructuredDataAdapter adapter = new PatentStructuredDataAdapter(knowledgeApi);

        StructuredQueryPreParser preParser = new StructuredQueryPreParser();
        StructuredQueryContextResolver contextResolver = new StructuredQueryContextResolver(List.of(adapter));
        StructuredQueryExecutor executor = new StructuredQueryExecutor(metricRegistry, List.of(adapter));
        StructuredAnswerRenderer renderer = new StructuredAnswerRenderer();
        CompletenessGuard guard = new CompletenessGuard();
        service = new StructuredQueryService(preParser, metricRegistry, entityRegistry, fieldRegistry, contextResolver,
                executor, renderer, guard);
    }

    @Test
    void caseA_countPatents_currentKb() {
        StructuredQueryService.HandleResult r = service.handle("当前知识库有几个专利？", 1L, DOMAIN, List.of());
        assertEquals(StructuredQueryService.State.ANSWER, r.state());
        assertEquals(QueryType.AGGREGATE, r.plan().getQueryType());
        assertEquals("DOCUMENT_COUNT", r.plan().getMetricCode());
        assertEquals(Operation.COUNT, r.plan().getOperation());
        assertEquals(QueryScopeType.CURRENT_KB, r.plan().getScope().getType());
        assertEquals(3, r.result().getValue().intValue());
        assertTrue(r.answer().contains("3"));
    }

    @Test
    void caseB_sumClaims_threePatentsWithContext() {
        List<ChatTurnDTO> history = List.of(turn("USER", "对比 202311042981.1、202311042982.2 和 202311042983.3"));
        StructuredQueryService.HandleResult r = service.handle("三个专利共有多少项权利要求？", 1L, DOMAIN, history);
        assertEquals(StructuredQueryService.State.ANSWER, r.state());
        assertEquals("CLAIM_COUNT", r.plan().getMetricCode());
        assertEquals(Operation.SUM, r.plan().getOperation());
        assertEquals(QueryScopeType.DOCUMENT_SET, r.plan().getScope().getType());
        assertEquals(19, r.result().getValue().intValue()); // 7+3+9
        assertTrue(r.answer().contains("19"));
        assertTrue(r.answer().contains("7项"));
        assertTrue(r.answer().contains("3项"));
        assertTrue(r.answer().contains("9项"));
    }

    @Test
    void caseB_withoutContext_clarify() {
        // 用户说"三个专利"但历史无可定位对象 → 必须 CLARIFY, 禁止随机取前三个
        StructuredQueryService.HandleResult r = service.handle("三个专利共有多少项权利要求？", 1L, DOMAIN, List.of());
        assertEquals(StructuredQueryService.State.CLARIFY, r.state());
        assertTrue(r.clarificationQuestion() != null && r.clarificationQuestion().contains("哪"));
    }

    @Test
    void caseC_sumClaims_currentKb() {
        StructuredQueryService.HandleResult r = service.handle("当前知识库共有多少项权利要求？", 1L, DOMAIN, List.of());
        assertEquals(StructuredQueryService.State.ANSWER, r.state());
        assertEquals(Operation.SUM, r.plan().getOperation());
        assertEquals(QueryScopeType.CURRENT_KB, r.plan().getScope().getType());
        assertEquals(19, r.result().getValue().intValue());
    }

    @Test
    void caseD_maxClaims_whichPatent() {
        StructuredQueryService.HandleResult r = service.handle("哪个专利的权利要求最多？", 1L, DOMAIN, List.of());
        assertEquals(StructuredQueryService.State.ANSWER, r.state());
        assertEquals(Operation.MAX, r.plan().getOperation());
        assertEquals(9, r.result().getValue().intValue());
        assertTrue(r.answer().contains("专利C"));
    }

    @Test
    void caseE_groupClaims_threePatents() {
        List<ChatTurnDTO> history = List.of(turn("USER", "看看 202311042981.1、202311042982.2 和 202311042983.3 的权利要求"));
        StructuredQueryService.HandleResult r = service.handle("这三个专利分别有多少项权利要求？", 1L, DOMAIN, history);
        assertEquals(StructuredQueryService.State.ANSWER, r.state());
        assertEquals(QueryType.GROUP, r.plan().getQueryType());
        assertTrue(r.answer().contains("专利A"));
        assertTrue(r.answer().contains("7 项"));
        assertTrue(r.answer().contains("专利C"));
        assertTrue(r.answer().contains("9 项"));
    }

    @Test
    void caseF_sumClaims_currentKbAlias() {
        // "专利要求" 是 CLAIM_COUNT 在 Patent 领域下的同义词(问题原文为"专利要求")
        StructuredQueryService.HandleResult r = service.handle("当前知识库一共有多少个专利要求？", 1L, DOMAIN, List.of());
        assertEquals(StructuredQueryService.State.ANSWER, r.state());
        assertEquals("CLAIM_COUNT", r.plan().getMetricCode());
        assertEquals(Operation.SUM, r.plan().getOperation());
        assertEquals(19, r.result().getValue().intValue());
    }

    @Test
    void caseG_topN_claims() {
        StructuredQueryService.HandleResult r = service.handle("权利要求最多的三件专利是哪几个？", 1L, DOMAIN, List.of());
        assertEquals(StructuredQueryService.State.ANSWER, r.state());
        assertEquals(QueryType.TOP_N, r.plan().getQueryType());
        assertEquals(3, r.plan().getLimit());
        assertEquals(SortDirection.DESC, r.plan().getSort());
        assertTrue(r.answer().contains("专利C"));
        assertTrue(r.answer().contains("9"));
        assertTrue(r.answer().contains("专利A"));
    }

    @Test
    void caseH_scopeReferenceWithoutContext_clarify() {
        StructuredQueryService.HandleResult r = service.handle("这三个呢？", 1L, DOMAIN, List.of());
        assertEquals(StructuredQueryService.State.CLARIFY, r.state());
    }

    @Test
    void independentClaimCount_declaredButUnsupported() {
        // INDEPENDENT_CLAIM_COUNT 已定义, 但独立/从属计数数据源未提取 → 明确不可作答, 不猜
        StructuredQueryService.HandleResult r = service.handle(
                "当前知识库的独立权利要求数量一共有多少？", 1L, DOMAIN, List.of());
        assertEquals(StructuredQueryService.State.UNANSWERABLE, r.state());
    }

    @Test
    void metricRegistry_claimCountAliases() {
        DomainMetricRegistry registry = new DefaultDomainMetricRegistry();
        DefaultDomainEntityRegistry entities = new DefaultDomainEntityRegistry();
        DefaultDomainFieldRegistry fieldRegistry = new DefaultDomainFieldRegistry();
        new PatentStructuredPack(registry, entities, fieldRegistry);
        MetricDefinition m = registry.findByAlias(DOMAIN, "专利要求数量").orElse(null);
        assertNotNull(m);
        assertEquals("CLAIM_COUNT", m.getMetricCode());
        assertTrue(registry.findByAlias(DOMAIN, "权项数").isPresent());
        assertTrue(registry.findByAlias(DOMAIN, "权利要求数量").isPresent());
        assertTrue(registry.findByAlias(DOMAIN, "专利数量").isPresent());
    }

    @Test
    void metricRegistry_independentAndDependentDefined() {
        DomainMetricRegistry registry = new DefaultDomainMetricRegistry();
        DefaultDomainFieldRegistry fieldRegistry = new DefaultDomainFieldRegistry();
        new PatentStructuredPack(registry, new DefaultDomainEntityRegistry(), fieldRegistry);
        assertTrue(registry.lookup(DOMAIN, "INDEPENDENT_CLAIM_COUNT").isPresent());
        assertTrue(registry.lookup(DOMAIN, "DEPENDENT_CLAIM_COUNT").isPresent());
    }

    @Test
    void coreWithoutPatentPack_metricUnregistered() {
        // 未注册 Patent Pack 的纯 Core: 结构化候选 → 领域无注册指标 → NOT_STRUCTURED(交还 RAG)
        DefaultDomainMetricRegistry metricRegistry = new DefaultDomainMetricRegistry();
        DefaultDomainEntityRegistry entityRegistry = new DefaultDomainEntityRegistry();
        DefaultDomainFieldRegistry fieldRegistry = new DefaultDomainFieldRegistry();
        PatentStructuredDataAdapter adapter = new PatentStructuredDataAdapter(knowledgeApi);
        StructuredQueryPreParser preParser = new StructuredQueryPreParser();
        StructuredQueryContextResolver contextResolver = new StructuredQueryContextResolver(List.of(adapter));
        StructuredQueryExecutor executor = new StructuredQueryExecutor(metricRegistry, List.of());
        StructuredAnswerRenderer renderer = new StructuredAnswerRenderer();
        CompletenessGuard guard = new CompletenessGuard();
        StructuredQueryService bare = new StructuredQueryService(preParser, metricRegistry, entityRegistry,
                fieldRegistry, contextResolver, executor, renderer, guard);
        StructuredQueryService.HandleResult r = bare.handle("当前知识库有几个专利？", 1L, DOMAIN, List.of());
        assertEquals(StructuredQueryService.State.NOT_STRUCTURED, r.state());
    }

    // ========== mock ==========

    private void mockStructuredQuery() {
        when(knowledgeApi.structuredQuery(any())).thenAnswer(inv -> {
            StructuredQueryReqDTO req = inv.getArgument(0);
            List<Long> resolved = req.getResolvedEntityIds() == null ? List.of() : req.getResolvedEntityIds();
            StructuredQueryRespDTO resp = new StructuredQueryRespDTO();
            resp.setRows(new ArrayList<>());
            for (Patent p : patents) {
                if (!resolved.isEmpty() && !resolved.contains(p.docId())) continue;
                StructuredQueryRowDTO row = new StructuredQueryRowDTO();
                row.setDocumentId(p.docId());
                row.setDocumentName(p.name());
                row.setApplicationNo(p.appNo());
                row.setPublicationNo(p.pubNo());
                row.setValue("CLAIM_COUNT".equalsIgnoreCase(req.getMetricCode())
                        ? (double) p.claimCount() : 1d);
                resp.getRows().add(row);
            }
            return CommonResult.success(resp);
        });
    }

    private void mockLookupPatentDocuments() {
        when(knowledgeApi.lookupPatentDocuments(any())).thenAnswer(inv -> {
            PatentDocumentLookupReqDTO req = inv.getArgument(0);
            List<Long> ids = new ArrayList<>();
            for (Patent p : patents) {
                if (p.appNo().equals(req.getApplicationNo()) || p.pubNo().equals(req.getPublicationNo())) {
                    ids.add(p.docId());
                }
            }
            return CommonResult.success(ids);
        });
    }

    private ChatTurnDTO turn(String role, String content) {
        ChatTurnDTO t = new ChatTurnDTO();
        t.setRole(role);
        t.setContent(content);
        return t;
    }
}
