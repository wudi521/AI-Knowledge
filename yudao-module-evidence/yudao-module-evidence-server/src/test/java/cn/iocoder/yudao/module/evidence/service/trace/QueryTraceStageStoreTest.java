package cn.iocoder.yudao.module.evidence.service.trace;

import cn.iocoder.yudao.module.evidence.dal.dataobject.evidence.QueryTraceStageDO;
import cn.iocoder.yudao.module.evidence.dal.mysql.evidence.QueryTraceStageMapper;
import cn.iocoder.yudao.module.retrieval.api.dto.QueryStageTimingDTO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryTraceStageStoreTest {

    @Test
    void replaceMustDeleteOldRowsThenPersistOrderedStages() {
        QueryTraceStageMapper mapper = mock(QueryTraceStageMapper.class);
        QueryTraceStageStore store = new QueryTraceStageStore(mapper);

        QueryStageTimingDTO plan = stage(1, "AGENT_PLAN", "SUCCEEDED", 12L, "goal=q", "capability=structured_query");
        QueryStageTimingDTO answer = stage(2, "AGENT_ANSWER", "SUCCEEDED", 1L, "coverage=FULL", "answer ready");

        store.replace("ag-replay-1", List.of(plan, answer));

        verify(mapper).delete(any());
        ArgumentCaptor<QueryTraceStageDO> captor = ArgumentCaptor.forClass(QueryTraceStageDO.class);
        verify(mapper, times(2)).insert(captor.capture());
        List<QueryTraceStageDO> rows = captor.getAllValues();
        assertEquals("ag-replay-1", rows.get(0).getTraceId());
        assertEquals(1, rows.get(0).getSeq());
        assertEquals("AGENT_PLAN", rows.get(0).getStage());
        assertEquals(2, rows.get(1).getSeq());
        assertEquals("AGENT_ANSWER", rows.get(1).getStage());
    }

    @Test
    void findMustRebuildReplayDtoWithoutInventingSkippedState() {
        QueryTraceStageMapper mapper = mock(QueryTraceStageMapper.class);
        QueryTraceStageStore store = new QueryTraceStageStore(mapper);
        QueryTraceStageDO row = QueryTraceStageDO.builder()
                .id(10L)
                .traceId("ag-replay-2")
                .seq(3)
                .stage("AGENT_GUARD")
                .status("STOPPED")
                .elapsedMs(5L)
                .errorCode("NO_RELIABLE_EVIDENCE")
                .inputSummary("coverage=PARTIAL")
                .outputSummary("fail closed")
                .build();
        when(mapper.selectList(any())).thenReturn(List.of(row));

        List<QueryStageTimingDTO> replay = store.find("ag-replay-2");

        assertEquals(1, replay.size());
        QueryStageTimingDTO actual = replay.get(0);
        assertEquals(3, actual.getSeq());
        assertEquals("AGENT_GUARD", actual.getStage());
        assertEquals("NO_RELIABLE_EVIDENCE", actual.getErrorCode());
        assertFalse(Boolean.TRUE.equals(actual.getSkipped()));
        assertEquals("fail closed", actual.getOutputSummary());
    }

    private QueryStageTimingDTO stage(int seq, String name, String status, long elapsed,
                                      String input, String output) {
        QueryStageTimingDTO dto = new QueryStageTimingDTO();
        dto.setSeq(seq);
        dto.setStage(name);
        dto.setStatus(status);
        dto.setElapsedMs(elapsed);
        dto.setSkipped(false);
        dto.setInputSummary(input);
        dto.setOutputSummary(output);
        return dto;
    }
}
