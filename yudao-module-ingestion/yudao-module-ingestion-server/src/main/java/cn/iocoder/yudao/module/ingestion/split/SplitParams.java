package cn.iocoder.yudao.module.ingestion.split;

import java.util.HashMap;
import java.util.Map;

/**
 * 切分通用参数(所有策略共用 + 策略自定义扩展)
 */
public class SplitParams {

    /** 单块上限(token 估算口径: 1.5 字符/token) */
    private int maxTokens = 500;

    /** 句子级重叠数(默认 0, 可配 1~2 缓解边界切分丢关键句) */
    private int overlap = 0;

    /** 是否注入标题链(默认开: chunk 前缀带章节标题链, 保证上下文连贯) */
    private boolean titleChain = true;

    /** 策略自定义参数(如 structure 的 minSectionTokens 等) */
    private Map<String, Object> extra = new HashMap<>();

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public int getOverlap() {
        return overlap;
    }

    public void setOverlap(int overlap) {
        this.overlap = overlap;
    }

    public boolean isTitleChain() {
        return titleChain;
    }

    public void setTitleChain(boolean titleChain) {
        this.titleChain = titleChain;
    }

    public Map<String, Object> getExtra() {
        return extra;
    }

    public void setExtra(Map<String, Object> extra) {
        this.extra = extra == null ? new HashMap<>() : extra;
    }

    public Object extra(String key) {
        return extra.get(key);
    }

    public int extraInt(String key, int def) {
        Object v = extra.get(key);
        return v instanceof Number n ? n.intValue() : def;
    }

    /** 由注解默认值构造 */
    public static SplitParams from(ChunkStrategy ann) {
        SplitParams p = new SplitParams();
        if (ann != null) {
            p.setMaxTokens(ann.maxTokens());
            p.setOverlap(ann.overlap());
        }
        return p;
    }

    /** 单参数构造(String 兼容入口用) */
    public static SplitParams of(int maxTokens) {
        SplitParams p = new SplitParams();
        p.setMaxTokens(maxTokens);
        return p;
    }

    /** 由文档级参数 JSON 覆盖默认(仅覆盖存在的键) */
    public static SplitParams merge(SplitParams base, String paramsJson) {
        SplitParams p = new SplitParams();
        p.setMaxTokens(base.getMaxTokens());
        p.setOverlap(base.getOverlap());
        p.setTitleChain(base.isTitleChain());
        p.setExtra(base.getExtra());
        if (paramsJson == null || paramsJson.isBlank()) {
            return p;
        }
        try {
            cn.hutool.json.JSONObject json = cn.hutool.json.JSONUtil.parseObj(paramsJson);
            Integer maxTokens = json.getInt("maxTokens");
            if (maxTokens != null && maxTokens > 0) {
                p.setMaxTokens(maxTokens);
            }
            Integer overlap = json.getInt("overlap");
            if (overlap != null && overlap >= 0) {
                p.setOverlap(overlap);
            }
            Boolean titleChain = json.getBool("titleChain");
            if (titleChain != null) {
                p.setTitleChain(titleChain);
            }
            cn.hutool.json.JSONObject extraJson = json.getJSONObject("extra");
            if (extraJson != null) {
                for (String key : extraJson.keySet()) {
                    p.getExtra().put(key, extraJson.get(key));
                }
            }
        } catch (Exception ignored) {
            // 参数 JSON 非法时忽略, 使用默认参数
        }
        return p;
    }

    /** 默认参数(供策略列表接口展示) */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("maxTokens", maxTokens);
        m.put("overlap", overlap);
        m.put("titleChain", titleChain);
        return m;
    }
}
