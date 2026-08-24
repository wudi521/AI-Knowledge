package cn.iocoder.yudao.module.evidence.service.semantics;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.retrieval.api.RetrievalApi;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalResultDTO;
import cn.iocoder.yudao.module.retrieval.api.dto.RetrievalSearchRespDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExactTextExecutionServiceTest {

    @Mock RetrievalApi retrievalApi;

    @Test
    void existenceQueryUsesRealTotalButAllowsTruncatedPreview() {
        ExactTextExecutionService service = new ExactTextExecutionService(retrievalApi);
        when(retrievalApi.search(any())).thenReturn(CommonResult.success(response(21L, 20)));

        ExactTextExecutionService.Result result = service.execute(
                "原文是否包含“测试短语”？", "测试短语", 6L, List.of(), 1L, 9L, "q-1");

        assertThat(result.answerable()).isTrue();
        assertThat(result.totalHits()).isEqualTo(21L);
        assertThat(result.truncated()).isTrue();
        assertThat(result.reasonCode()).isNull();
        assertThat(result.answer()).contains("实际命中 21 个片段").contains("当前仅展示前 20 个");
        assertThat(result.answer()).doesNotContain("不能把下列片段视为完整清单");
    }

    @Test
    void exhaustiveQueryExplicitlyMarksTruncationAsIncomplete() {
        ExactTextExecutionService service = new ExactTextExecutionService(retrievalApi);
        when(retrievalApi.search(any())).thenReturn(CommonResult.success(response(21L, 20)));

        ExactTextExecutionService.Result result = service.execute(
                "哪些地方出现“测试短语”？", "测试短语", 6L, List.of(), 1L, 9L, "q-2");

        assertThat(result.answerable()).isTrue();
        assertThat(result.reasonCode()).isEqualTo("EXACT_TEXT_RESULT_TRUNCATED");
        assertThat(result.answer()).contains("实际命中 21 个片段")
                .contains("当前仅展示前 20 个")
                .contains("不能把下列片段视为完整清单")
                .contains("请缩小知识库、文档或其它查询范围");
    }

    private RetrievalSearchRespDTO response(long totalHits, int count) {
        RetrievalSearchRespDTO resp = new RetrievalSearchRespDTO();
        resp.setTotalHits(totalHits);
        List<RetrievalResultDTO> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            RetrievalResultDTO row = new RetrievalResultDTO();
            row.setChunkId((long) i + 1);
            row.setDocumentId(100L + i);
            row.setDocumentName("文档" + (i + 1));
            row.setContent("上下文 测试短语 上下文");
            row.setRrfScore(1D);
            row.setChannels(List.of("exact_text"));
            rows.add(row);
        }
        resp.setResults(rows);
        return resp;
    }
}
