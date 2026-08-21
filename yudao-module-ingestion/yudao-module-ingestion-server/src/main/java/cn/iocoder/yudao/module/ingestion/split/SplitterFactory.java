package cn.iocoder.yudao.module.ingestion.split;

import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 切分策略选择器(插件化): 构造时收集 Spring 容器中全部 {@link ChunkSplitter} Bean,
 * 读取 {@link ChunkStrategy} 注解注册; 新增策略 = 新增类 + 注解, 零改动本工厂。
 * <p>
 * auto 逻辑: 文档未选策略/未知 key 时按内容特征自动委派——有标题层级→structure;
 * 表格元素占比高→table; 条款编号结构→policy; 否则 semantic。
 */
@Component
public class SplitterFactory {

    /** key → 切分器(不含 auto) */
    private final Map<String, ChunkSplitter> splitters = new LinkedHashMap<>();
    /** key → 注解元数据 */
    private final Map<String, ChunkStrategy> metadata = new LinkedHashMap<>();

    public SplitterFactory(List<ChunkSplitter> splitterList) {
        for (ChunkSplitter s : splitterList) {
            ChunkStrategy ann = s.getClass().getAnnotation(ChunkStrategy.class);
            if (ann == null || StrUtil.isBlank(ann.key()) || "auto".equals(ann.key())) {
                continue; // 无注解或 auto(内部逻辑)不注册
            }
            splitters.put(ann.key(), s);
            metadata.put(ann.key(), ann);
        }
    }

    /**
     * 按策略 key 获取切分器; 空值/未知 key → auto 逻辑(确定性行为, 不静默回退 Semantic)
     */
    public ChunkSplitter getSplitter(String strategy) {
        if (StrUtil.isBlank(strategy) || "auto".equals(strategy)) {
            return this::autoSplit;
        }
        ChunkSplitter s = splitters.get(strategy);
        return s != null ? s : this::autoSplit;
    }

    /**
     * 全部已注册策略(含 auto), 供前端下拉与入库校验
     */
    public List<StrategyInfo> listStrategies() {
        List<StrategyInfo> list = new ArrayList<>();
        list.add(new StrategyInfo("auto", "自动选择",
                "按文档内容自动判定切分策略(推荐: 无需业务选择)", SplitParams.of(500).toMap()));
        for (Map.Entry<String, ChunkStrategy> e : metadata.entrySet()) {
            ChunkStrategy ann = e.getValue();
            list.add(new StrategyInfo(ann.key(), ann.name(), ann.description(),
                    SplitParams.from(ann).toMap()));
        }
        return list;
    }

    /** 校验策略 key 是否已注册(未知返回 false; auto/空返回 true 由 auto 兜底) */
    public boolean isValid(String strategy) {
        return StrUtil.isBlank(strategy) || "auto".equals(strategy) || splitters.containsKey(strategy);
    }

    /**
     * auto 委派: 按内容特征选择策略
     */
    private List<Chunk> autoSplit(ParsedDocument doc, SplitParams params) {
        if (doc == null || doc.isEmpty()) {
            return List.of();
        }
        int headings = 0;
        int tables = 0;
        int total = doc.getElements().size();
        for (ParsedDocument.Element e : doc.getElements()) {
            if (e instanceof ParsedDocument.HeadingElement) {
                headings++;
            } else if (e instanceof ParsedDocument.TableElement) {
                tables++;
            }
        }
        String target;
        if (headings > 0) {
            target = "structure"; // 有标题层级 → 结构切分(精准度+连贯性基线)
        } else if (total > 0 && (double) tables / total > 0.4) {
            target = "table";     // 表格为主 → 表格切分
        } else if (hasPolicyStructure(doc)) {
            target = "policy";    // 条款编号结构 → 条款切分
        } else {
            target = "semantic";  // 无结构文本 → 语义切分
        }
        ChunkSplitter s = splitters.get(target);
        return s != null ? s.split(doc, params) : List.of();
    }

    /** 条款结构检测: 前若干元素文本含 "第X条"/"X.X" 编号 */
    private boolean hasPolicyStructure(ParsedDocument doc) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "(?:第[一二三四五六七八九十百0-9]+条|[0-9]+\\.[0-9]+)");
        int checked = 0;
        for (ParsedDocument.Element e : doc.getElements()) {
            if (e instanceof ParsedDocument.ParagraphElement && p.matcher(e.text()).find()) {
                return true;
            }
            if (++checked >= 5) {
                break;
            }
        }
        return false;
    }

    /** 策略信息(列表接口返回) */
    public record StrategyInfo(String key, String name, String description, Map<String, Object> defaultParams) {
    }
}
