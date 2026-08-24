package cn.iocoder.yudao.module.chat.service.context.model;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * SubsetExpression(子集表达式, CQ-06)
 * <p>
 * Core 统一实现 FIRST_N/LAST_N/INDEX/EXCLUDE_INDEX/ALL; CARDINALITY 表示"这 N 个"(数量引用,
 * 数量与引用集合 size 一致才可解析, 否则由 Resolver CLARIFY, 禁止随便取前 N 个)。
 */
@Data
@Builder
public class SubsetExpression {

    public enum Type {
        ALL, FIRST_N, LAST_N, INDEX, EXCLUDE_INDEX, EXCLUDE_FIRST_N, CARDINALITY
    }

    private Type type;

    /** INDEX/EXCLUDE_INDEX 的序号(0-based) */
    private Integer index;

    /** FIRST_N/LAST_N/CARDINALITY 的数量 */
    private Integer count;

    /** 空结果(引用集合为空) */
    public static SubsetExpression none() {
        return null;
    }

    /**
     * 应用到保序实体 id 列表。
     *
     * @return 子集 id 列表; 若 CARDINALITY 数量与 size 不匹配则返回 null(调用方需 CLARIFY)
     */
    public List<Long> apply(List<Long> orderedIds) {
        if (orderedIds == null || orderedIds.isEmpty()) {
            return orderedIds;
        }
        int size = orderedIds.size();
        switch (type) {
            case ALL:
                return new ArrayList<>(orderedIds);
            case FIRST_N: {
                int n = count == null ? size : Math.min(size, Math.max(0, count));
                return new ArrayList<>(orderedIds.subList(0, n));
            }
            case LAST_N: {
                int n = count == null ? size : Math.min(size, Math.max(0, count));
                return new ArrayList<>(orderedIds.subList(size - n, size));
            }
            case INDEX: {
                int i = normalizeIndex(index, size);
                if (i < 0 || i >= size) {
                    return new ArrayList<>();
                }
                return List.of(orderedIds.get(i));
            }
            case EXCLUDE_INDEX: {
                int i = normalizeIndex(index, size);
                List<Long> result = new ArrayList<>(size - 1);
                for (int j = 0; j < size; j++) {
                    if (j != i) {
                        result.add(orderedIds.get(j));
                    }
                }
                return result;
            }
            case EXCLUDE_FIRST_N: {
                int n = count == null ? 0 : Math.min(size, Math.max(0, count));
                return new ArrayList<>(orderedIds.subList(n, size));
            }
            case CARDINALITY: {
                // 数量必须与引用集合 size 一致才可全取; 否则无法确定具体是哪 N 个 → null 触发 CLARIFY
                if (count == null || count != size) {
                    return null;
                }
                return new ArrayList<>(orderedIds);
            }
            default:
                return new ArrayList<>(orderedIds);
        }
    }

    /** 支持"第一个/第二个..." 1-based 与 0-based; 越界返回 -1 */
    private int normalizeIndex(Integer raw, int size) {
        if (raw == null) {
            return -1;
        }
        int i = raw >= 1 ? raw - 1 : raw; // 1-based 转 0-based; 0 视为 0-based 直接透传
        return i;
    }

}
