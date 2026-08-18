package cn.iocoder.yudao.module.retrieval.service.search;

import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RRF 融合: score = Σ 1/(k + rank), k=60
 */
@Service
public class RrfMerger {

    private static final int K = 60;

    /**
     * 融合多个排序列表(rank 从 1 开始)
     *
     * @param rankedLists 多个通道的排序结果列表
     * @param topN 返回条数
     * @return 按融合分降序的 [chunkId, score] 列表
     */
    public List<Map.Entry<Long, Double>> merge(List<List<Map.Entry<Long, Double>>> rankedLists, int topN) {
        Map<Long, Double> scores = new HashMap<>();
        for (List<Map.Entry<Long, Double>> list : rankedLists) {
            for (int i = 0; i < list.size(); i++) {
                Long chunkId = list.get(i).getKey();
                scores.merge(chunkId, 1.0 / (K + i + 1), Double::sum);
            }
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey())) // 分数相同按 chunkId, 保证确定性
                .limit(topN)
                .collect(Collectors.toList());
    }

}
