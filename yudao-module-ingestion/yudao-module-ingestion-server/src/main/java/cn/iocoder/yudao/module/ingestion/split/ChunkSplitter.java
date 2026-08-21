package cn.iocoder.yudao.module.ingestion.split;

import java.util.List;

/**
 * 切分器: 把结构化文档按策略切成片段(插件化: 实现类加 {@link ChunkStrategy} 注解自动注册)
 */
public interface ChunkSplitter {

    /**
     * 基于结构化文档切分
     *
     * @param doc    解析产物(结构树)
     * @param params 切分参数(块大小/重叠/标题链开关/策略扩展)
     * @return 片段列表
     */
    List<Chunk> split(ParsedDocument doc, SplitParams params);

    /**
     * 纯文本切分(兼容入口: 内部包装为无结构 ParsedDocument)
     *
     * @param text      全文
     * @param maxTokens 单块最大 token 数
     */
    default List<Chunk> split(String text, int maxTokens) {
        return split(ParsedDocument.ofText(text), SplitParams.of(maxTokens));
    }

}
