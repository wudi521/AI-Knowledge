package cn.iocoder.yudao.module.evidence.service.agent.capability;

import cn.iocoder.yudao.module.evidence.domain.Evidence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Domain 层向公共 Runtime 注册的关系遍历适配器。
 *
 * <p>公共 Runtime/Tool 不知道“专利引用”“套餐归属”“合同附件”等业务关系名称；
 * Domain Provider 只声明当前领域真实可执行的 relationTypes 并负责访问实际数据源。</p>
 */
public interface DomainRelationProvider {

    String domainCode();

    Set<String> relationTypes();

    RelationResult traverse(RelationRequest request);

    record RelationRequest(CapabilityInvocationContext context,
                           List<Long> sourceEntityIds,
                           String relationType,
                           Direction direction,
                           int limit) {
        public RelationRequest {
            sourceEntityIds = sourceEntityIds == null ? List.of() : List.copyOf(sourceEntityIds);
        }
    }

    enum Direction {
        OUT,
        IN,
        BOTH
    }

    record RelationResult(Map<Long, List<Long>> edges,
                          List<Evidence> evidences,
                          boolean complete,
                          String message,
                          Map<String, Object> metadata) {
        public RelationResult {
            Map<Long, List<Long>> safeEdges = new LinkedHashMap<>();
            if (edges != null) {
                for (Map.Entry<Long, List<Long>> entry : edges.entrySet()) {
                    if (entry.getKey() == null) continue;
                    LinkedHashSet<Long> ids = new LinkedHashSet<>();
                    if (entry.getValue() != null) {
                        for (Long id : entry.getValue()) if (id != null) ids.add(id);
                    }
                    safeEdges.put(entry.getKey(), List.copyOf(ids));
                }
            }
            edges = Collections.unmodifiableMap(safeEdges);
            evidences = evidences == null ? List.of() : List.copyOf(new ArrayList<>(evidences));
            metadata = metadata == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        }

        public static RelationResult complete(Map<Long, List<Long>> edges, List<Evidence> evidences) {
            return new RelationResult(edges, evidences, true, null, Map.of());
        }
    }
}
