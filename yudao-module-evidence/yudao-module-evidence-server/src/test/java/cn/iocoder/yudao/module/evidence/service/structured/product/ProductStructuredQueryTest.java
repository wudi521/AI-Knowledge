package cn.iocoder.yudao.module.evidence.service.structured.product;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CQ-48 Fake PRODUCT domain 回归矩阵(0 LLM 确定性路径)。
 * <p>
 * 验证 Platform Core 领域无关性: 同一 StructuredQueryService 仅换 Product Pack + 内存 Adapter,
 * 即支持产品 count/价格聚合/品类 LIST/语义执行 fallback —— Core 不出现 PRODUCT 领域分支。
 */
class ProductStructuredQueryTest {

    private static final String DOMAIN = ProductStructuredPack.DOMAIN_CODE;

    private StructuredQueryService service;

    @BeforeEach
    void setUp() {
        DefaultDomainMetricRegistry metricRegistry = new DefaultDomainMetricRegistry();
        DefaultDomainEntityRegistry entityRegistry = new DefaultDomainEntityRegistry();
        DefaultDomainFieldRegistry fieldRegistry = new DefaultDomainFieldRegistry();
        new ProductStructuredPack(metricRegistry, entityRegistry, fieldRegistry);
        ProductStructuredDataAdapter adapter = new ProductStructuredDataAdapter();

        StructuredQueryPreParser preParser = new StructuredQueryPreParser();
        StructuredQueryContextResolver contextResolver = new StructuredQueryContextResolver(List.of(adapter));
        StructuredQueryExecutor executor = new StructuredQueryExecutor(metricRegistry, List.of(adapter));
        StructuredAnswerRenderer renderer = new StructuredAnswerRenderer();
        CompletenessGuard guard = new CompletenessGuard();
        service = new StructuredQueryService(preParser, metricRegistry, entityRegistry, fieldRegistry,
                contextResolver, executor, renderer, guard);
    }

    /** 多轮: chat 侧已消解实体集 */
    private String handleIds(String query, List<Long> ids) {
        return service.handle(query, 6L, DOMAIN, List.of(), ids, null).answer();
    }

    @Test
    void productCount() {
        String answer = service.handle("当前知识库有多少个产品？", 6L, DOMAIN, List.of()).answer();
        assertNotNull(answer);
        assertTrue(answer.contains("3"), answer);
    }

    @Test
    void priceSum() {
        String answer = handleIds("这些产品的价格一共多少？", List.of(201L, 202L, 203L));
        assertNotNull(answer);
        assertTrue(answer.contains("9997"), answer); // 3999+4999+999
    }

    @Test
    void priceMax_returnsHighestProduct() {
        String answer = handleIds("价格最高的产品是哪个？", List.of(201L, 202L, 203L));
        assertNotNull(answer);
        assertTrue(answer.contains("手机B") || answer.contains("4999"), answer);
    }

    @Test
    void priceAvg() {
        String answer = handleIds("这些产品的平均价格是多少？", List.of(201L, 202L, 203L));
        assertNotNull(answer);
        assertTrue(answer.contains("3332") || answer.contains("3333"), answer); // (3999+4999+999)/3
    }

    @Test
    void categoryList() {
        String answer = handleIds("它们分别是什么品类？", List.of(201L, 202L, 203L));
        assertNotNull(answer);
        assertTrue(answer.contains("手机") && answer.contains("耳机"), answer);
    }

    @Test
    void skuFieldWithFieldCodeHint() {
        String answer = service.handle("它们的SKU是多少？", 6L, DOMAIN, List.of(),
                List.of(201L), ProductStructuredPack.FIELD_SKU).answer();
        assertNotNull(answer);
        assertTrue(answer.contains("SKU-A01"), answer);
    }

    @Test
    void semanticFallback_returnsSemanticState() {
        // CQ-38: 无 metric/field 但已有实体集 → PER_ENTITY_SEMANTIC(不 CLARIFY 防猜)
        StructuredQueryService.HandleResult r = service.handle("它们的技术参数分别是什么？", 6L, DOMAIN, List.of(),
                List.of(201L, 202L), null);
        assertEquals(State.SEMANTIC, r.state());
        assertNotNull(r.semanticEntityIds());
        assertEquals(List.of(201L, 202L), r.semanticEntityIds());
    }

    @Test
    void withoutEntitySet_semanticStillClarifies() {
        StructuredQueryService.HandleResult r = service.handle("技术参数分别是什么？", 6L, DOMAIN, List.of(),
                null, null);
        assertEquals(State.CLARIFY, r.state());
        assertEquals("MISSING_METRIC", r.reasonCode());
    }
}
