package cn.iocoder.yudao.module.chat.channel.web;

import cn.iocoder.yudao.module.chat.channel.ChannelAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * WEB 渠道适配器
 * <p>
 * WEB 渠道下 HTTP 请求/响应本身就是渠道载体, 本适配器主要负责:
 * <ul>
 *     <li>声明渠道身份 "WEB"(管线渠道解析的兜底目标);</li>
 *     <li>暴露 {@link #isEnabled()} 开关(供 Task 5 Controller 侧在渠道禁用时拒绝请求, 管线不做拒绝)。</li>
 * </ul>
 * 后续可在此扩展 WEB 渠道专属限制(如单会话频率、字数上限)。
 */
@Slf4j
@Component
public class WebChannelAdapter implements ChannelAdapter {

    @Value("${yudao.chat.channels.web.enabled:true}")
    private boolean webEnabled;

    @Override
    public String channel() {
        return "WEB";
    }

    /**
     * WEB 渠道是否启用(默认 true; 禁用时由 Controller 层拒绝请求)
     */
    public boolean isEnabled() {
        return webEnabled;
    }

}
