package cn.iocoder.yudao.module.ingestion.split;

import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 结构切分(精准度 + 上下文连贯性基线): 按标题层级把文档切成"章节子树"块。
 * <p>
 * 每块 = 标题链前缀 + 该章节下全部元素文本(段落/表格/列表/图片描述);
 * 章节超长按句子折叠, 折叠块仍带标题链前缀, 保证每块自包含且上下文完整。
 */
@Component
@ChunkStrategy(key = "structure", name = "结构切分",
        description = "按标题层级切块, 每块=章节子树(标题链+段落+表格+图片), 超限按句子折叠; 保证精准度与上下文连贯")
public class StructureSplitter implements ChunkSplitter {

    @Override
    public List<Chunk> split(ParsedDocument doc, SplitParams params) {
        List<Chunk> result = new ArrayList<>();
        if (doc == null || doc.isEmpty()) {
            return result;
        }
        int maxTokens = params == null ? 500 : params.getMaxTokens();
        int overlap = params == null ? 0 : params.getOverlap();
        boolean titleChain = params == null || params.isTitleChain();

        // 标题栈(存 level + 文本), 维护当前章节链
        Deque<ParsedDocument.HeadingElement> headingStack = new ArrayDeque<>();
        List<String> groupTexts = new ArrayList<>();
        // 遍历元素, 遇到标题收尾上一组并更新链
        for (ParsedDocument.Element e : doc.getElements()) {
            if (e instanceof ParsedDocument.HeadingElement h) {
                flushGroup(result, headingStack, groupTexts, maxTokens, overlap, titleChain);
                while (!headingStack.isEmpty() && headingStack.peek().getLevel() >= h.getLevel()) {
                    headingStack.pop();
                }
                headingStack.push(h);
            } else {
                String t = e.text();
                if (StrUtil.isNotBlank(t)) {
                    groupTexts.add(t);
                }
            }
        }
        flushGroup(result, headingStack, groupTexts, maxTokens, overlap, titleChain);
        return result;
    }

    /** 收尾当前章节组: 生成块(整组或折叠), 带标题链前缀 */
    private void flushGroup(List<Chunk> result, Deque<ParsedDocument.HeadingElement> headingStack,
                            List<String> groupTexts, int maxTokens, int overlap, boolean titleChain) {
        if (groupTexts.isEmpty()) {
            groupTexts.clear();
            return;
        }
        // 标题链(栈底→栈顶)
        List<String> chain = new ArrayList<>();
        for (ParsedDocument.HeadingElement h : headingStack) {
            chain.add(0, h.text());
        }
        String prefix = titleChain ? SplitUtils.titleChainPrefix(chain) : "";
        String body = String.join("\n", groupTexts);
        groupTexts.clear();

        if (SplitUtils.estimateTokens(body) <= maxTokens) {
            result.add(new Chunk(prefix + body, "STRUCTURE"));
            return;
        }
        // 章节超长: 按句子折叠, 每块仍带标题链前缀
        List<String> blocks = SplitUtils.splitBySentences(body, maxTokens);
        if (overlap > 0) {
            blocks = SplitUtils.applyOverlap(blocks, overlap);
        }
        for (String block : blocks) {
            result.add(new Chunk(prefix + block, "STRUCTURE"));
        }
    }
}
