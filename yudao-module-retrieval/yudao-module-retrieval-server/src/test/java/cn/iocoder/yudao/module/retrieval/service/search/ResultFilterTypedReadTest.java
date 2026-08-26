package cn.iocoder.yudao.module.retrieval.service.search;

import cn.iocoder.yudao.module.ingestion.api.IngestionApi;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResultFilterTypedReadTest {

    @Test
    void visibilityFailureIsTypedWhileLegacyFacadeRemainsEmptyCompatible() throws Exception {
        KnowledgeApi knowledgeApi = mock(KnowledgeApi.class);
        IngestionApi ingestionApi = mock(IngestionApi.class);
        when(knowledgeApi.getVisibleKbIds(9L)).thenThrow(new RuntimeException("knowledge unavailable"));
        ResultFilter filter = filter(knowledgeApi, ingestionApi);

        ResultFilter.ReadResult<Set<Long>> typed = filter.getVisibleKbIdsResult(9L);

        assertTrue(typed.failed());
        assertTrue(typed.data().isEmpty());
        assertTrue(filter.getVisibleKbIds(9L).isEmpty());
    }

    @Test
    void contentReadFailureCannotMasqueradeAsHealthyEmptyMap() throws Exception {
        KnowledgeApi knowledgeApi = mock(KnowledgeApi.class);
        IngestionApi ingestionApi = mock(IngestionApi.class);
        when(ingestionApi.getChunkContents(List.of(101L))).thenThrow(new RuntimeException("ingestion unavailable"));
        ResultFilter filter = filter(knowledgeApi, ingestionApi);

        ResultFilter.ReadResult<Map<Long, String>> typed = filter.getChunkContentsResult(List.of(101L));

        assertTrue(typed.failed());
        assertTrue(typed.data().isEmpty());
    }

    @Test
    void emptyInputIsHealthyEmptyWithoutUpstreamRead() throws Exception {
        ResultFilter filter = filter(mock(KnowledgeApi.class), mock(IngestionApi.class));

        ResultFilter.ReadResult<Map<Long, String>> typed = filter.getChunkContentsResult(List.of());

        assertFalse(typed.failed());
        assertTrue(typed.data().isEmpty());
    }

    private ResultFilter filter(KnowledgeApi knowledgeApi, IngestionApi ingestionApi) throws Exception {
        ResultFilter filter = new ResultFilter();
        set(filter, "knowledgeApi", knowledgeApi);
        set(filter, "ingestionApi", ingestionApi);
        return filter;
    }

    private void set(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
