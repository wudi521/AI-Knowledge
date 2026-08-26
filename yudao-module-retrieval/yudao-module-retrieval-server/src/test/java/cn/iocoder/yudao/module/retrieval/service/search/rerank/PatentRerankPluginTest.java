package cn.iocoder.yudao.module.retrieval.service.search.rerank;

import cn.iocoder.yudao.module.retrieval.service.domain.PatentQueryPreParser;
import cn.iocoder.yudao.module.retrieval.service.search.Reranker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PatentRerankPluginTest {

    @Test
    void exactApplicationNumberGateLivesInPatentPluginWhenNoScopeProvenanceExists() {
        Reranker generic = mock(Reranker.class);
        when(generic.rerank(anyString(), anyList())).thenReturn(List.of(
                Map.entry(0, 0.9F),
                Map.entry(1, 0.8F)));
        PatentRerankPlugin plugin = new PatentRerankPlugin(generic, new PatentQueryPreParser());
        String applicationNo = "202311832214.0";

        RetrievalRerankResult result = plugin.rerank(new RetrievalRerankContext(
                "申请号 " + applicationNo + " 的技术方案是什么？",
                List.of("其它专利内容", "申请号 " + applicationNo + " 的说明书内容"),
                "PATENT"));

        assertEquals(1, result.rankings().size());
        assertEquals(1, result.rankings().get(0).getKey());
    }

    @Test
    void authoritativeDocumentScopeMeansChunksNeedNotRepeatApplicationNumber() {
        Reranker generic = mock(Reranker.class);
        when(generic.rerank(anyString(), anyList())).thenReturn(List.of(
                Map.entry(0, 0.9F),
                Map.entry(1, 0.8F)));
        PatentRerankPlugin plugin = new PatentRerankPlugin(generic, new PatentQueryPreParser());
        String applicationNo = "202311832214.0";

        RetrievalRerankResult result = plugin.rerank(new RetrievalRerankContext(
                "申请号 " + applicationNo + " 的技术方案是什么？",
                List.of("飞行器采用倾转小翼实现垂直起降", "控制系统根据姿态调整舵面"),
                "PATENT", List.of(74L)));

        assertEquals(2, result.rankings().size());
        assertEquals(List.of(0, 1), result.rankings().stream().map(Map.Entry::getKey).toList());
    }
}
