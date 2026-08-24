package cn.iocoder.yudao.module.evidence.service.structured.core;

import cn.iocoder.yudao.module.evidence.api.dto.ChatTurnDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generic Structured Query Engine Core 测试(不依赖 Patent Domain Pack)。
 * <p>
 * 使用 TEST 假领域(entity=PRODUCT, metrics=PRODUCT_COUNT/PRICE)验证 Core 是领域无关的。
 * 若删除 Patent Domain Pack, 本测试仍应通过。
 */
class StructuredQueryEngineCoreTest {

    private static final String DOMAIN = "TEST";

    private TestDomainStructuredDataAdapter adapter;
    private StructuredQueryService service;

    @BeforeEach
    void setUp() {
        DefaultDomainMetricRegistry metricRegistry = new DefaultDomainMetricRegistry();
        DefaultDomainEntityRegistry entityRegistry = new DefaultDomainEntityRegistry();
        DefaultDomainFieldRegistry fieldRegistry = new DefaultDomainFieldRegistry();
        // TEST 假领域注册
        entityRegistry.register(EntityDefinition.builder().domainCode(DOMAIN).entityCode("PRODUCT")
                .displayLabel("产品").classifier("个").aliases(List.of("产品", "商品")).build());
        metricRegistry.register(MetricDefinition.builder().metricCode("PRODUCT_COUNT").domainCode(DOMAIN)
                .entityType("PRODUCT").valueType("INTEGER")
                .supportedOperations(Set.of(Operation.COUNT, Operation.COUNT_DISTINCT))
                .aliases(List.of("产品数量", "产品数")).displayName("产品").unit("个")
                .adapterKey("TEST").build());
        metricRegistry.register(MetricDefinition.builder().metricCode("PRICE").domainCode(DOMAIN)
                .entityType("PRODUCT").valueType("DECIMAL")
                .supportedOperations(Set.of(Operation.SUM, Operation.AVG, Operation.MIN, Operation.MAX))
                .aliases(List.of("价格", "单价", "售价")).displayName("价格").unit("元")
                .adapterKey("TEST").build());

        adapter = new TestDomainStructuredDataAdapter();
        StructuredQueryPreParser preParser = new StructuredQueryPreParser();
        StructuredQueryContextResolver contextResolver = new StructuredQueryContextResolver(List.of(adapter));
        StructuredQueryExecutor executor = new StructuredQueryExecutor(metricRegistry, List.of(adapter));
        StructuredAnswerRenderer renderer = new StructuredAnswerRenderer();
        CompletenessGuard guard = new CompletenessGuard();
        service = new StructuredQueryService(preParser, metricRegistry, entityRegistry, fieldRegistry, contextResolver,
                executor, renderer, guard);
    }

    @Test
    void countProducts_currentKb() {
        StructuredQueryService.HandleResult r = service.handle("知识库有几个产品？", 1L, DOMAIN, List.of());
        assertEquals(StructuredQueryService.State.ANSWER, r.state());
        assertEquals(QueryType.AGGREGATE, r.plan().getQueryType());
        assertEquals("PRODUCT_COUNT", r.plan().getMetricCode());
        assertEquals(Operation.COUNT, r.plan().getOperation());
        assertEquals(QueryScopeType.CURRENT_KB, r.plan().getScope().getType());
        assertEquals(4, r.result().getValue().intValue());
        assertTrue(r.answer().contains("4"));
    }

    @Test
    void sumPrice_documentSetFromHistory() {
        // 历史提到 P101/P102/P103 三个产品
        List<ChatTurnDTO> history = List.of(turn("USER", "对比一下 P101 和 P102、P103 的定价"));
        StructuredQueryService.HandleResult r = service.handle("这三个产品价格一共多少？", 1L, DOMAIN, history);
        assertEquals(StructuredQueryService.State.ANSWER, r.state());
        assertEquals("PRICE", r.plan().getMetricCode());
        assertEquals(Operation.SUM, r.plan().getOperation());
        assertEquals(QueryScopeType.DOCUMENT_SET, r.plan().getScope().getType());
        // P101=100, P102=50, P103=30
        assertEquals(180, r.result().getValue().intValue());
        assertTrue(r.answer().contains("180"));
    }

    @Test
    void sumPrice_scopeReferenceWithoutContext_clarify() {
        // 用户说"这三个产品"但历史没有可定位对象 → 必须 CLARIFY, 禁止随机取前三个
        StructuredQueryService.HandleResult r = service.handle("这三个产品价格一共多少？", 1L, DOMAIN, List.of());
        assertEquals(StructuredQueryService.State.CLARIFY, r.state());
        assertTrue(r.clarificationQuestion() != null && r.clarificationQuestion().contains("哪"));
    }

    @Test
    void avgPrice_documentSet() {
        List<ChatTurnDTO> history = List.of(turn("USER", "P101、P102、P103 哪个更好"));
        StructuredQueryService.HandleResult r = service.handle("这三个产品平均价格是多少？", 1L, DOMAIN, history);
        assertEquals(StructuredQueryService.State.ANSWER, r.state());
        assertEquals(Operation.AVG, r.plan().getOperation());
        // (100+50+30)/3 = 60
        assertEquals(60, r.result().getValue().intValue());
        assertTrue(r.answer().contains("60"));
    }

    @Test
    void maxPrice_whichProduct() {
        StructuredQueryService.HandleResult r = service.handle("哪个产品价格最高？", 1L, DOMAIN, List.of());
        assertEquals(StructuredQueryService.State.ANSWER, r.state());
        assertEquals(Operation.MAX, r.plan().getOperation());
        assertEquals(100, r.result().getValue().intValue());
        assertTrue(r.answer().contains("产品A"));
    }

    @Test
    void minPrice() {
        StructuredQueryService.HandleResult r = service.handle("哪个产品价格最低？", 1L, DOMAIN, List.of());
        assertEquals(StructuredQueryService.State.ANSWER, r.state());
        assertEquals(Operation.MIN, r.plan().getOperation());
        assertEquals(30, r.result().getValue().intValue());
    }

    @Test
    void listProducts() {
        StructuredQueryService.HandleResult r = service.handle("有哪些产品？", 1L, DOMAIN, List.of());
        assertEquals(StructuredQueryService.State.ANSWER, r.state());
        assertEquals(QueryType.LIST, r.plan().getQueryType());
        assertTrue(r.answer().contains("产品A"));
    }

    @Test
    void groupPrices_documentSet() {
        List<ChatTurnDTO> history = List.of(turn("USER", "P101、P102、P103 的价格分别是多少"));
        StructuredQueryService.HandleResult r = service.handle("这三个产品的价格分别是多少？", 1L, DOMAIN, history);
        assertEquals(StructuredQueryService.State.ANSWER, r.state());
        assertEquals(QueryType.GROUP, r.plan().getQueryType());
        assertTrue(r.answer().contains("产品A"));
        assertTrue(r.answer().contains("100"));
    }

    @Test
    void topN_highestPrice() {
        StructuredQueryService.HandleResult r = service.handle("价格最高的3个产品是哪几个？", 1L, DOMAIN, List.of());
        assertEquals(StructuredQueryService.State.ANSWER, r.state());
        assertEquals(QueryType.TOP_N, r.plan().getQueryType());
        assertEquals(3, r.plan().getLimit());
        assertEquals(SortDirection.DESC, r.plan().getSort());
        // 100/80/50 前三
        assertTrue(r.answer().contains("产品A"));
        assertTrue(r.answer().contains("100"));
    }

    @Test
    void topN_highestPrice_limitFromCardinality() {
        // "3个" 是 limit, 不是范围对象 → 无需历史, 直接整库 TOP_N
        StructuredQueryService.HandleResult r = service.handle("价格最高的3个产品？", 1L, DOMAIN, List.of());
        assertEquals(StructuredQueryService.State.ANSWER, r.state());
        assertEquals(QueryType.TOP_N, r.plan().getQueryType());
        assertEquals(3, r.plan().getLimit());
    }

    @Test
    void completenessGuard_detectsCompleteDatasetSemantics() {
        CompletenessGuard guard = new CompletenessGuard();
        assertTrue(guard.requiresCompleteDataset("知识库一共有多少产品"));
        assertTrue(guard.requiresCompleteDataset("平均价格是多少"));
        assertTrue(guard.requiresCompleteDataset("全部产品有哪些"));
        assertTrue(guard.requiresCompleteDataset("占比如何"));
        assertTrue(!guard.requiresCompleteDataset("产品A的说明书内容是什么"));
    }

    @Test
    void completenessGuard_structuredCandidateButExactLookupExcluded() {
        CompletenessGuard guard = new CompletenessGuard();
        assertTrue(guard.isStructuredCandidate("知识库有几个产品"));
        assertTrue(!guard.isStructuredCandidate("产品说明书内容是什么")); // 无聚合词 → 交还 RAG
        assertTrue(!guard.isStructuredCandidate("申请号 202311042981.1 有几个权利要求")); // 显式标识 → EXACT
    }

    @Test
    void topK_neverProvesCorpusTotal() {
        // 模拟 TopK 只有 3 条, 但用户问"一共多少产品" → 结构化候选走完整数据集, 不得用 TopK 数
        StructuredQueryService.HandleResult r = service.handle("知识库一共有多少产品？", 1L, DOMAIN, List.of());
        assertEquals(StructuredQueryService.State.ANSWER, r.state());
        assertEquals(Operation.COUNT, r.plan().getOperation());
        assertEquals(4, r.result().getValue().intValue()); // 来自完整数据集 4 条, 而非 TopK=3
    }

    @Test
    void notStructured_fallsThrough() {
        StructuredQueryService.HandleResult r = service.handle("产品A的说明书内容是什么", 1L, DOMAIN, List.of());
        assertEquals(StructuredQueryService.State.NOT_STRUCTURED, r.state());
    }

    private ChatTurnDTO turn(String role, String content) {
        ChatTurnDTO t = new ChatTurnDTO();
        t.setRole(role);
        t.setContent(content);
        return t;
    }

    /** TEST 假领域数据适配器(内存数据集; 同时实现实体解析器) */
    static class TestDomainStructuredDataAdapter implements DomainStructuredDataAdapter, DomainEntityResolver {

        private static final Pattern PRODUCT_ID = Pattern.compile("\\bP(\\d{3})\\b");

        /** 产品数据: id / name / key / price */
        record Product(Long id, String name, String key, Double price) {
        }

        final List<Product> products = new ArrayList<>(List.of(
                new Product(101L, "产品A", "P101", 100d),
                new Product(102L, "产品B", "P102", 50d),
                new Product(103L, "产品C", "P103", 30d),
                new Product(104L, "产品D", "P104", 80d)));

        @Override
        public String adapterKey() {
            return "TEST";
        }

        @Override
        public boolean supports(String metricCode) {
            return "PRODUCT_COUNT".equals(metricCode) || "PRICE".equals(metricCode);
        }

        @Override
        public StructuredQueryResult execute(StructuredQueryPlan plan) {
            List<Product> effective = plan.getScope() != null
                    && plan.getScope().getResolvedEntityIds() != null
                    && !plan.getScope().getResolvedEntityIds().isEmpty()
                    ? products.stream().filter(p -> plan.getScope().getResolvedEntityIds().contains(p.id())).toList()
                    : products;
            List<StructuredQueryResult.Row> rows = new ArrayList<>();
            for (Product p : effective) {
                double v = "PRODUCT_COUNT".equals(plan.getMetricCode()) ? 1d : p.price();
                rows.add(StructuredQueryResult.Row.builder().entityId(p.id()).entityKey(p.key())
                        .entityName(p.name()).value(v).build());
            }
            return StructuredQueryResult.builder().metricCode(plan.getMetricCode())
                    .operation(plan.getOperation()).rows(rows).rowCount(rows.size()).truncated(false).build();
        }

        @Override
        public String domainCode() {
            return "TEST";
        }

        @Override
        public List<ResolvedEntity> extractEntities(String text) {
            List<ResolvedEntity> out = new ArrayList<>();
            if (text == null) return out;
            Matcher m = PRODUCT_ID.matcher(text);
            while (m.find()) {
                out.add(new ResolvedEntity("P" + m.group(1), null, null));
            }
            return out;
        }

        @Override
        public List<ResolvedEntity> resolveToEntities(List<ResolvedEntity> entities, Long kbId) {
            List<ResolvedEntity> out = new ArrayList<>();
            if (entities == null) return out;
            for (ResolvedEntity e : entities) {
                for (Product p : products) {
                    if (p.key().equals(e.identifier())) {
                        out.add(new ResolvedEntity(e.identifier(), p.id(), p.name()));
                    }
                }
            }
            return out;
        }
    }
}
