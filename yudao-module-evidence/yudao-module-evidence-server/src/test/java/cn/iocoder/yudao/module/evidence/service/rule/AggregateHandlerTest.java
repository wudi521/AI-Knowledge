package cn.iocoder.yudao.module.evidence.service.rule;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AG-11: 知识库聚合确定性处理单测
 * <p>
 * 覆盖: 计数识别 / metric 选择 / 已发布过滤 / count=0 / LIST 类拒绝 /
 * 不触发向量(不依赖 retrieval 即可完成)。
 */
class AggregateHandlerTest {

    private KnowledgeApi knowledgeApi;
    private AggregateHandler handler;

    @BeforeEach
    void setUp() {
        knowledgeApi = mock(KnowledgeApi.class);
        handler = new AggregateHandler();
        // 注入私有字段(无 @Resource 容器)
        java.lang.reflect.Field field;
        try {
            field = AggregateHandler.class.getDeclaredField("knowledgeApi");
            field.setAccessible(true);
            field.set(handler, knowledgeApi);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private AggregateHandler.AggregateResult eval(String query, Long kbId) {
        return handler.evaluate(query, List.of(kbId));
    }

    @Test
    void count3PublishedPatents() {
        when(knowledgeApi.aggregateCount(eq(6L), eq("PATENT_COUNT"), eq(true), eq("PATENT")))
                .thenReturn(CommonResult.success(3));
        AggregateHandler.AggregateResult r = eval("当前知识库有几个专利？", 6L);
        assertEquals(3, r.value());
        assertEquals("PATENT_COUNT", r.metric().name());
        assertTrue(r.answer().contains("3"));
    }

    @Test
    void count0Patents() {
        when(knowledgeApi.aggregateCount(eq(9L), eq("PATENT_COUNT"), eq(true), eq("PATENT")))
                .thenReturn(CommonResult.success(0));
        AggregateHandler.AggregateResult r = eval("当前知识库有几个专利？", 9L);
        assertEquals(0, r.value());
        assertTrue(r.answer().contains("0"));
    }

    @Test
    void documentCountMetric() {
        when(knowledgeApi.aggregateCount(eq(6L), eq("DOCUMENT_COUNT"), eq(true), isNull()))
                .thenReturn(CommonResult.success(4));
        AggregateHandler.AggregateResult r = eval("当前知识库有多少篇文档？", 6L);
        assertEquals("DOCUMENT_COUNT", r.metric().name());
        assertTrue(r.answer().contains("4"));
    }

    @Test
    void knowledgeEntryCountMetric() {
        when(knowledgeApi.aggregateCount(eq(6L), eq("KNOWLEDGE_ENTRY_COUNT"), eq(true), isNull()))
                .thenReturn(CommonResult.success(45));
        AggregateHandler.AggregateResult r = eval("这个知识库一共有多少条知识？", 6L);
        assertEquals("KNOWLEDGE_ENTRY_COUNT", r.metric().name());
        assertTrue(r.answer().contains("45"));
    }

    @Test
    void listIntentReturnsNull_noAggregate() {
        // LIST 类(有哪些/分别有哪些)无聚合引擎 → null, 交 Completeness Guard 拒绝, 不猜
        assertNull(eval("知识库有哪些专利？", 6L));
        assertNull(eval("分别有哪些已发布专利？", 6L));
        verifyNoInteractions(knowledgeApi);
    }

    @Test
    void nonAggregateQueryReturnsNull() {
        assertNull(eval("专利202311042981.1的权利要求1是什么", 6L));
        verifyNoInteractions(knowledgeApi);
    }

    @Test
    void multiKbOrNullKbReturnsNull() {
        assertNull(handler.evaluate("有几个专利", List.of(6L, 7L)));
        assertNull(handler.evaluate("有几个专利", List.of()));
        verifyNoInteractions(knowledgeApi);
    }

    @Test
    void alwaysPublishedOnlyAndPatentDomain() {
        when(knowledgeApi.aggregateCount(eq(6L), eq("PATENT_COUNT"), eq(true), eq("PATENT")))
                .thenReturn(CommonResult.success(3));
        eval("现在发布了多少份专利？", 6L);
        // publishedOnly=true + domainCode=PATENT 必须传递
        verify(knowledgeApi).aggregateCount(eq(6L), eq("PATENT_COUNT"), eq(true), eq("PATENT"));
    }

    @Test
    void completenessIntentDetector() {
        assertTrue(AggregateHandler.isCompletenessIntent("有几个专利"));
        assertTrue(AggregateHandler.isCompletenessIntent("知识库一共有多少篇文档"));
        assertTrue(AggregateHandler.isCompletenessIntent("全部专利有哪些"));
        assertTrue(AggregateHandler.isCompletenessIntent("平均权利要求数是多少"));
        assertTrue(!AggregateHandler.isCompletenessIntent("专利202311042981.1的权利要求1是什么"));
    }
}
