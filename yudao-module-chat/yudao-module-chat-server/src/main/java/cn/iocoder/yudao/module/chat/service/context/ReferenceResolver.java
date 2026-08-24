package cn.iocoder.yudao.module.chat.service.context;

import cn.iocoder.yudao.module.chat.service.context.model.ContextFrame;
import cn.iocoder.yudao.module.chat.service.context.model.QueryContextResolution;
import cn.iocoder.yudao.module.chat.service.context.model.ResultSetSnapshot;
import cn.iocoder.yudao.module.chat.service.context.model.SubsetExpression;
import cn.iocoder.yudao.module.chat.service.context.model.SubsetExpression.Type;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * ReferenceResolver(CQ-04~08): 解析当前查询对历史上下文的引用(指代/序号/子集/数量)。
 * <p>
 * 规则: ①明确实体(申请号/公布号)优先覆盖(EXPLICIT_ENTITY); ②否则从上下文帧栈最近→远按
 * entityType 匹配找可引用结果集; ③应用子集表达式; ④数量与引用集合不一致 → CLARIFY(禁止随便取前 N)。
 */
@Slf4j
@Component
public class ReferenceResolver {

    private static final Pattern APPLICATION_NO = Pattern.compile("20\\d{10}\\.\\d");
    private static final Pattern PUBLICATION_NO = Pattern.compile("CN\\s?\\d{8,12}\\s?[A-Z]");

    @Resource
    private ResultSetService resultSetService;

    /**
     * @param query          当前用户问题
     * @param frames         上下文帧栈(最近在前)
     * @param entityTypeHint 期望的实体类型(由调用方从 Domain 判断; 可空则用最近帧)
     */
    public QueryContextResolution resolve(String query, List<ContextFrame> frames, String entityTypeHint) {
        // CQ-07: 明确实体永远优先于历史上下文
        if (hasExplicitEntity(query)) {
            return QueryContextResolution.explicitEntity();
        }
        ContextFrame refFrame = findRefFrame(frames, entityTypeHint);
        if (refFrame == null || refFrame.getResultSetId() == null) {
            return QueryContextResolution.noReference();
        }
        SubsetExpression subset = SubsetParser.parse(query);
        if (subset == null) {
            subset = SubsetExpression.builder().type(Type.ALL).build();
        }
        ResultSetSnapshot rs = resultSetService.getResultSet(refFrame.getResultSetId());
        if (rs == null || ResultSetSnapshot.STATUS_STALE.equals(rs.getStatus())) {
            return QueryContextResolution.clarify("上一轮的结果已不可用，请重新查询。", "STALE_RESULT_SET");
        }
        List<Long> applied = subset.apply(resultSetService.materialize(rs));
        if (applied == null) {
            // CQ-05: 数量与引用集合不一致且无明确子集 → CLARIFY
            int count = subset.getCount() == null ? 0 : subset.getCount();
            return QueryContextResolution.clarify(
                    "上一轮共有 " + rs.getEntityCount() + " 个" + label(rs.getEntityType())
                            + "，请明确是哪 " + count + " 个？", "AMBIGUOUS_SCOPE");
        }
        return QueryContextResolution.builder()
                .scopeType(QueryContextResolution.SCOPE_PREVIOUS_RESULT_SET)
                .resultSetId(rs.getResultSetId())
                .entityType(rs.getEntityType())
                .subset(subset)
                .explicitEntityIds(applied)
                .build();
    }

    /** 从帧栈最近→远找可引用帧: 优先 entityType 匹配且带 resultSetId; 否则最近带 resultSetId */
    private ContextFrame findRefFrame(List<ContextFrame> frames, String entityTypeHint) {
        if (frames == null || frames.isEmpty()) {
            return null;
        }
        ContextFrame fallback = null;
        for (ContextFrame f : frames) {
            if (f.getResultSetId() == null) {
                continue;
            }
            if (fallback == null) {
                fallback = f;
            }
            if (entityTypeHint != null && entityTypeHint.equals(f.getEntityType())) {
                return f;
            }
        }
        return fallback;
    }

    private boolean hasExplicitEntity(String query) {
        if (query == null) {
            return false;
        }
        return APPLICATION_NO.matcher(query).find() || PUBLICATION_NO.matcher(query).find();
    }

    private String label(String entityType) {
        if ("PATENT_DOCUMENT".equals(entityType)) {
            return "专利";
        }
        return entityType == null ? "对象" : entityType;
    }

}
