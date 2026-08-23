package cn.iocoder.yudao.module.chat.framework.chat;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对话工作台业务配置(前缀 yudao.chat.*)
 * <p>
 * 绑定 yudao.chat.max-context-messages 等配置项(kebab-case 自动映射 camelCase),
 * 供 {@code ChatPipeline} 读取历史上下文轮数上限 —— 与 {@link TransferProperties}
 * (前缀 yudao.chat.transfer.*) 互补, 两者互不依赖。
 */
@Data
@ConfigurationProperties(prefix = "yudao.chat")
public class ChatProperties {

    /** 注入证据评估的历史上下文最大消息条数(USER/AI, 不含 SYSTEM; 默认 6) */
    private int maxContextMessages = 6;

    /** 流式 delta 切片字符数(模型网关为同步返回, 后端对最终答案切片推送, 默认 60) */
    private int streamChunkSize = 60;

    /** 流式 delta 切片间隔 ms(默认 10; 0 表示不额外等待) */
    private long streamChunkDelayMs = 10;

    /** 流式问答 SSE 超时 ms(默认 180s; 模型调用单次可达 30~120s) */
    private long streamTimeoutMs = 180_000;

}
