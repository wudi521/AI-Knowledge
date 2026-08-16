package cn.iocoder.yudao.module.ingestion.embedding;

import cn.iocoder.yudao.module.model.api.ModelApi;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 向量化客户端: Feign 调 model-server 的 embedding 接口
 */
@Component
public class EmbeddingClient {

    @Resource
    private ModelApi modelApi;

    /**
     * 批量向量化
     *
     * @param texts 文本列表
     * @return 每个文本的 1024 维向量
     */
    public List<List<Float>> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        // ModelApi.embedding 返回 CommonResult, 这里取 data
        return modelApi.embedding(texts).getData();
    }

}
