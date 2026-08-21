package cn.iocoder.yudao.module.ingestion.parse;

import cn.iocoder.yudao.module.ingestion.split.ParsedDocument;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 上下文增强(切分前预处理, 精准度+连贯性通用机制):
 * 1. 标题链回填: 元素归属其所在章节标题链(结构切分据此注入前缀);
 * 2. 图片上下文绑定: 图片元素填充 前文摘要(contextBefore, 最近 800 字内的前 200 字),
 *    并调用视觉模型生成描述(描述缺失时降级为上下文占位, 不阻断);
 * 3. 相邻合并提示: 无(合并由切分器在块层面处理)。
 */
@Slf4j
@Component
public class ContextEnricher {

    /** 前文摘要窗口(超过该长度截断, 保留尾部) */
    private static final int RECENT_WINDOW = 800;
    /** 注入图片的摘要长度 */
    private static final int CONTEXT_PREFIX = 200;

    @Resource
    private ImageProcessor imageProcessor;

    public ParsedDocument enrich(ParsedDocument doc) {
        if (doc == null || doc.isEmpty()) {
            return doc;
        }
        Deque<LevelTitle> headingStack = new ArrayDeque<>();
        StringBuilder recentText = new StringBuilder();
        for (ParsedDocument.Element e : doc.getElements()) {
            if (e instanceof ParsedDocument.HeadingElement h) {
                // 新章节: 弹出层级 ≥ 当前标题的栈顶, 压入当前标题
                while (!headingStack.isEmpty() && headingStack.peek().level >= h.getLevel()) {
                    headingStack.pop();
                }
                headingStack.push(new LevelTitle(h.getLevel(), h.text()));
                recentText.setLength(0); // 章节切换, 前文摘要重置
                continue;
            }
            // 非标题元素: 回填标题链
            List<String> chain = new ArrayList<>();
            for (LevelTitle lt : headingStack) {
                chain.add(0, lt.title);
            }
            e.setTitleChain(chain);
            if (e instanceof ParsedDocument.ImageElement img) {
                img.setContextBefore(tail(recentText, CONTEXT_PREFIX));
                if (img.getDescription() == null && imageProcessor.isEnabled()) {
                    try {
                        String desc = imageProcessor.describeImage(img.getImageRef(), img.getContextBefore());
                        if (desc != null && !desc.isBlank()) {
                            img.setDescription(desc.trim());
                        }
                    } catch (Exception ex) {
                        log.warn("[enrich][图片理解异常, 降级跳过: {}]", ex.getMessage());
                    }
                }
                // 图片描述本身也纳入前文(后续图片可引用前图描述)
                appendRecent(recentText, img.text());
            } else {
                appendRecent(recentText, e.text());
            }
        }
        return doc;
    }

    private void appendRecent(StringBuilder sb, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (sb.length() > RECENT_WINDOW) {
            sb.delete(0, sb.length() - RECENT_WINDOW);
        }
        sb.append(text).append('\n');
        if (sb.length() > RECENT_WINDOW) {
            sb.delete(0, sb.length() - RECENT_WINDOW);
        }
    }

    /** 前文摘要: 取尾部 min(len, limit) 字符 */
    private String tail(StringBuilder sb, int limit) {
        if (sb.length() == 0) {
            return "";
        }
        int start = Math.max(0, sb.length() - limit);
        String s = sb.substring(start);
        // 截断到句子边界, 避免半句
        int cut = s.indexOf('。');
        if (cut > 0 && s.length() - cut < limit / 2) {
            s = s.substring(cut + 1);
        }
        return s.trim();
    }

    /** 标题栈元素(层级 + 文本) */
    private record LevelTitle(int level, String title) {
    }
}
