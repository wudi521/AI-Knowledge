package cn.iocoder.yudao.module.chat.enums.chat;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 统一执行阶段枚举(QueryStageEvent / Query Trace 同源)
 * <p>
 * 阶段仅描述"可审计执行过程"(问题识别/目标锁定/检索/重排/证据/生成/校验/修复),
 * 禁止映射模型隐藏思维链。与检索侧 {@code QueryStageTimingDTO.stage} 字符串保持一致,
 * 未知阶段降级为原始 code(向前兼容)。
 */
public enum QueryStageEnum {

    ANALYZE("ANALYZE", "问题理解"),
    ROUTE("ROUTE", "路由判定"),
    REWRITE("REWRITE", "问题改写"),
    SCOPE_FILTER("SCOPE_FILTER", "范围过滤"),
    DOC_LOOKUP("DOC_LOOKUP", "文档锁定"),
    CLAIM_LOOKUP("CLAIM_LOOKUP", "权利要求定位"),
    BM25("BM25", "关键词检索"),
    VECTOR("VECTOR", "向量检索"),
    FUSION("FUSION", "检索融合"),
    RERANK("RERANK", "相关性重排"),
    EVIDENCE("EVIDENCE", "证据筛选"),
    GENERATE("GENERATE", "生成回答"),
    VERIFY("VERIFY", "证据校验"),
    REPAIR("REPAIR", "回答修复"),
    ANSWER("ANSWER", "完成回答");

    private final String code;
    private final String label;

    private static final Map<String, QueryStageEnum> BY_CODE = Stream.of(values())
            .collect(Collectors.toMap(QueryStageEnum::getCode, Function.identity()));

    QueryStageEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    /** 按阶段编码查找; 未知编码返回 null(调用方使用原始 code 兜底) */
    public static QueryStageEnum of(String code) {
        return code == null ? null : BY_CODE.get(code);
    }

}
