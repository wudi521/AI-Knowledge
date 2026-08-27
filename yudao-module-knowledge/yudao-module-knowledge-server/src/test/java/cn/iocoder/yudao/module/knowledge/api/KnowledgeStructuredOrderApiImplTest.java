package cn.iocoder.yudao.module.knowledge.api;

import cn.iocoder.yudao.module.knowledge.api.dto.StructuredOrderReqDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredOrderRespDTO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.PatentStructuredOrderStatsDO;
import cn.iocoder.yudao.module.knowledge.dal.mysql.knowledge.PatentStructuredOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeStructuredOrderApiImplTest {

    @Mock
    private PatentStructuredOrderMapper mapper;

    private KnowledgeStructuredOrderApiImpl api;

    @BeforeEach
    void setUp() {
        api = new KnowledgeStructuredOrderApiImpl(mapper);
    }

    @Test
    void titleLengthOrderReturnsTopIdsWithCompleteProof() {
        when(mapper.selectTitleLengthStats(6L, null, true)).thenReturn(stats(9L, 0L, 0L));
        when(mapper.selectTopByTitleLength(6L, null, true, "DESC", 1)).thenReturn(List.of(88L));

        StructuredOrderRespDTO data = api.order(request("TITLE", "LENGTH", "DESC", 1)).getData();

        assertTrue(data.isCompleteDataset());
        assertEquals(9L, data.getSourceEntityCount());
        assertEquals(0L, data.getMissingValueCount());
        assertEquals(0L, data.getConflictCount());
        assertEquals(List.of(88L), data.getDocumentIds());
    }

    @Test
    void missingRequiredTitleProducesProofButNoCandidateIds() {
        when(mapper.selectTitleLengthStats(6L, null, true)).thenReturn(stats(9L, 1L, 0L));

        StructuredOrderRespDTO data = api.order(request("TITLE", "LENGTH", "DESC", 1)).getData();

        assertTrue(data.isCompleteDataset());
        assertEquals(1L, data.getMissingValueCount());
        assertTrue(data.getDocumentIds().isEmpty());
        verify(mapper, never()).selectTopByTitleLength(anyLong(), any(), anyBoolean(), anyString(), anyInt());
    }

    @Test
    void duplicateTitleConflictProducesProofButNoCandidateIds() {
        when(mapper.selectTitleLengthStats(6L, null, true)).thenReturn(stats(9L, 0L, 1L));

        StructuredOrderRespDTO data = api.order(request("TITLE", "LENGTH", "ASC", 2)).getData();

        assertEquals(1L, data.getConflictCount());
        assertTrue(data.getDocumentIds().isEmpty());
        verify(mapper, never()).selectTopByTitleLength(anyLong(), any(), anyBoolean(), anyString(), anyInt());
    }

    @Test
    void unregisteredFieldTransformCannotReachDatabase() {
        StructuredOrderReqDTO bad = request("FILING_DATE", "LENGTH", "DESC", 1);

        assertThrows(IllegalArgumentException.class, () -> api.order(bad));
        verify(mapper, never()).selectTitleLengthStats(anyLong(), any(), anyBoolean());
    }

    @Test
    void arbitraryDirectionAndOversizedLimitAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> api.order(request("TITLE", "LENGTH", "RANDOM()", 1)));
        assertThrows(IllegalArgumentException.class,
                () -> api.order(request("TITLE", "LENGTH", "DESC", 51)));
        verify(mapper, never()).selectTitleLengthStats(anyLong(), any(), anyBoolean());
    }

    private StructuredOrderReqDTO request(String field, String transform, String direction, int limit) {
        StructuredOrderReqDTO req = new StructuredOrderReqDTO();
        req.setKbId(6L);
        req.setDomainCode("PATENT");
        req.setFieldCode(field);
        req.setTransformCode(transform);
        req.setDirection(direction);
        req.setLimit(limit);
        req.setPublishedOnly(true);
        return req;
    }

    private PatentStructuredOrderStatsDO stats(long source, long missing, long conflicts) {
        PatentStructuredOrderStatsDO stats = new PatentStructuredOrderStatsDO();
        stats.setSourceEntityCount(source);
        stats.setMissingValueCount(missing);
        stats.setConflictCount(conflicts);
        return stats;
    }
}
