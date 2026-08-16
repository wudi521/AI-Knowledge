package cn.iocoder.yudao.module.ingestion.split;

import java.util.List;

/**
 * 切分器: 把全文按策略切成片段
 */
public interface ChunkSplitter {

    /**
     * 切分文本
     *
     * @param text 全文
     * @param maxTokens 单块最大 token 数(默认 500)
     * @return 片段列表
     */
    List<Chunk> split(String text, int maxTokens);

}
