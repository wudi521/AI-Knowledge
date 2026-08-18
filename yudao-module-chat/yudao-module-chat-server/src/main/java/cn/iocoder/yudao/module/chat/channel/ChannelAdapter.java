package cn.iocoder.yudao.module.chat.channel;

/**
 * 会话渠道适配器(领域无关抽象)
 * <p>
 * 每个渠道一个实现, 通过 Spring 注入 {@code List<ChannelAdapter>} 供 {@code ChatPipeline} 做渠道解析。
 * 当前仅 WEB 渠道注册(HTTP 请求/响应即渠道本体); 企微 / 钉钉 / APP 等渠道后续按需新增实现,
 * 未注册的渠道名一律降级为 WEB(不报错, 见 {@code ChatPipeline#resolveChannel})。
 */
public interface ChannelAdapter {

    /**
     * 渠道标识: WEB / WECHAT / DINGTALK / APP
     */
    String channel();

    /**
     * 是否支持指定渠道(忽略大小写)
     */
    default boolean supports(String channel) {
        return channel().equalsIgnoreCase(channel);
    }

}
