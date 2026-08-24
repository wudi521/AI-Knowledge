package cn.iocoder.yudao.module.chat.service.context.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * QueryContextResolution(CQ-04~10): chat 侧多轮上下文解析结果, 传入 evidence 执行。
 * <p>
 * scopeType: PREVIOUS_RESULT_SET(引用历史结果集) / EXPLICIT_ENTITY(明确实体覆盖) / CURRENT_KB(无引用)。
 * clarifyRequired 时直接返回澄清问题, 不进入执行。
 */
@Data
@Builder
public class QueryContextResolution {

    public static final String SCOPE_PREVIOUS_RESULT_SET = "PREVIOUS_RESULT_SET";
    public static final String SCOPE_EXPLICIT_ENTITY = "EXPLICIT_ENTITY";
    public static final String SCOPE_CURRENT_KB = "CURRENT_KB";

    /** 范围类型 */
    private String scopeType;

    /** 引用的历史结果集编号 */
    private String resultSetId;

    /** 引用的实体类型(如 PATENT_DOCUMENT) */
    private String entityType;

    /** 子集表达式(引用场景) */
    private SubsetExpression subset;

    /** 应用子集后的具体实体 id(引用场景; 数量不匹配时为空并置 clarifyRequired) */
    private List<Long> explicitEntityIds;

    /** 是否需要澄清(数量不一致/歧义/权限变化改变语义) */
    private boolean clarifyRequired;

    /** 澄清问题 */
    private String clarifyQuestion;

    /** 拒绝/澄清原因码(CQ-39: AMBIGUOUS_SCOPE/STALE_RESULT_SET/PERMISSION_CHANGED/EMPTY_RESULT_SET 等) */
    private String reasonCode;

    /** 继承/显式的指标编码 */
    private String metricCode;

    /** 继承/显式的字段编码 */
    private String fieldCode;

    /** 继承/显式的聚合运算 */
    private String operation;

    /** 引用结果集是否存在(权限/删除等导致不可用) */
    private Boolean contextChanged;

    public static QueryContextResolution noReference() {
        return QueryContextResolution.builder().scopeType(SCOPE_CURRENT_KB).build();
    }

    public static QueryContextResolution explicitEntity() {
        return QueryContextResolution.builder().scopeType(SCOPE_EXPLICIT_ENTITY).build();
    }

    public static QueryContextResolution clarify(String question, String reasonCode) {
        return QueryContextResolution.builder()
                .scopeType(SCOPE_CURRENT_KB)
                .clarifyRequired(true)
                .clarifyQuestion(question)
                .reasonCode(reasonCode)
                .build();
    }

}
