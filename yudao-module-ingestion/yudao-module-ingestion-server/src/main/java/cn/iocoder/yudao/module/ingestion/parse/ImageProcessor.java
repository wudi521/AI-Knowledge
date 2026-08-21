package cn.iocoder.yudao.module.ingestion.parse;

/**
 * 图片理解 SPI: 把图片生成面向检索的中文描述文本(描述参与切分与向量化)。
 * 实现失败返回 null, 由调用方降级为上下文占位块(不阻断入库)。
 */
public interface ImageProcessor {

    /**
     * 生成图片描述
     *
     * @param imageRef    图片引用(base64 data URL 或 http(s) URL)
     * @param contextText 图片所属上下文(章节标题/前文摘要, 可空)
     * @return 描述文本; 失败返回 null
     */
    String describeImage(String imageRef, String contextText);

    /**
     * 视觉模型是否可用(未启用 image 类型模型时返回 false, 调用方跳过图片处理)
     */
    boolean isEnabled();
}
