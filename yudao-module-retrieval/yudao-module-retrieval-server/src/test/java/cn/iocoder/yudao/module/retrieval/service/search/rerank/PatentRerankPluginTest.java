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
    void exactApplicationNumberGateLivesInPatentPluginNotGenericReranker() {
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
}
