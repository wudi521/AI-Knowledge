package cn.iocoder.yudao.module.chat.framework.chat;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话转人工业务配置
 * <p>
 * 绑定 yudao.chat.transfer.* 配置项(kebab-case 自动映射 camelCase), 供 {@code TransferHandler}
 * 结构化关键词转人工判定读取 —— 全部触发词来自配置, 代码不硬编码。
 */
@Data
@ConfigurationProperties(prefix = "yudao.chat.transfer")
public class TransferProperties {

    /** 转人工触发关键词: 命中任一 → 原因"客户要求" */
    private List<String> keywords = new ArrayList<>(List.of("人工", "客服", "转人工", "投诉", "赔偿", "举报"));

    /** 情绪激烈触发关键词: 命中任一 → 原因"情绪激烈"(优先级高于普通关键词) */
    private List<String> emotionKeywords = new ArrayList<>(List.of("太差", "垃圾", "滚", "差评", "起诉"));

}
