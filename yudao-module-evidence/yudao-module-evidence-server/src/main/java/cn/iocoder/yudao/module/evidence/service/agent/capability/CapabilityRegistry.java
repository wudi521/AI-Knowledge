package cn.iocoder.yudao.module.evidence.service.agent.capability;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredPipelinePlan;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryLanguageCapability;
import cn.iocoder.yudao.module.evidence.service.structured.core.StructuredQueryLanguageCatalog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class CapabilityRegistry {
    private static final String STRUCTURED_QUERY = "structured_query";
    private static final Set<String> LEGACY_STRUCTURED_PLANNER_ARGUMENTS = Set.of(
            "task", "field", "operator", "values", "projections", "metric", "operation", "sort", "transforms"
    );

    private final Map<String, KnowledgeCapability> capabilities;
    private final List<CapabilityVisibilityPolicy> visibilityPolicies;
    private final StructuredQueryLanguageCatalog queryLanguageCatalog;

    @Autowired
    public CapabilityRegistry(List<KnowledgeCapability> capabilityList,
                              List<CapabilityVisibilityPolicy> visibilityPolicies,
                              StructuredQueryLanguageCatalog queryLanguageCatalog) {
        Map<String, KnowledgeCapability> map = new LinkedHashMap<>();
        if (capabilityList != null) {
            for (KnowledgeCapability capability : capabilityList) {
                String name = capability.definition().name();
                if (map.putIfAbsent(name, capability) != null) throw new IllegalStateException("duplicate capability: " + name);
            }
        }
        this.capabilities = Collections.unmodifiableMap(map);
        this.visibilityPolicies = visibilityPolicies == null ? List.of() : List.copyOf(visibilityPolicies);
        this.queryLanguageCatalog = queryLanguageCatalog;
    }

    /** 兼容既有纯 Java 单测；Spring 正式运行使用三参数构造并注入运行时 Query IR 目录。 */
    public CapabilityRegistry(List<KnowledgeCapability> capabilityList,
                              List<CapabilityVisibilityPolicy> visibilityPolicies) {
        this(capabilityList, visibilityPolicies, null);
    }

    public KnowledgeCapability getVisible(String name, CapabilityInvocationContext context) {
        KnowledgeCapability capability = capabilities.get(name);
        if (capability == null) return null;
        CapabilityDefinition plannerDefinition = capability.plannerDefinition(context);
        return plannerDefinition != null && isVisible(plannerDefinition, context) ? capability : null;
    }

    /** Planner 只能看到当前 scope/domain 下真实可用、并已按上下文收窄后的 Tool Contract。 */
    public List<CapabilityDefinition> listDefinitions(CapabilityInvocationContext context) {
        List<CapabilityDefinition> out = new ArrayList<>();
        for (KnowledgeCapability capability : capabilities.values()) {
            CapabilityDefinition plannerDefinition = capability.plannerDefinition(context);
            if (plannerDefinition != null && isVisible(plannerDefinition, context)) {
                out.add(withPlannerGuidance(plannerDefinition, context));
            }
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * 在 Tool Contract 边界补充领域无关的关系代数/结果形态规则。
     *
     * <p>这里不识别“姓氏/重复文档”等用户意图，只声明稳定执行语义。structured_query 额外做一件
     * 关键事情：执行端继续兼容旧 task/TOP_N 参数，但 Planner-facing contract 隐藏这些迁移参数，
     * 只允许模型使用组合式 Query IR，并把运行时插件发现到的语言能力动态写入 contract。</p>
     */
    private CapabilityDefinition withPlannerGuidance(CapabilityDefinition definition,
                                                     CapabilityInvocationContext context) {
        Map<String, String> schema = definition.argumentSchema();
        Map<String, String> enriched = schema == null ? new LinkedHashMap<>() : new LinkedHashMap<>(schema);
        StringBuilder description = new StringBuilder(definition.description());

        if (STRUCTURED_QUERY.equals(definition.name())) {
            // 旧参数仍留在机器执行契约做兼容，但禁止 Planner 再走 task/TOP_N/单算子菜单式规划。
            for (String legacy : LEGACY_STRUCTURED_PLANNER_ARGUMENTS) enriched.remove(legacy);
            List<StructuredQueryLanguageCapability> languages = queryLanguageCatalog == null ? List.of()
                    : queryLanguageCatalog.capabilities(context == null ? null : context.domainCode());
            description.append(" Planner-facing contract=").append(StructuredPipelinePlan.IR_VERSION)
                    .append("；必须组合 select/filter/groupBy/aggregate/having/orderBy/distinct/limit 表达目标，")
                    .append("不得发明业务 intent 或依赖兼容 task 枚举。")
                    .append(" COUNT/SUM/AVG/MIN/MAX 等是 Query IR 语言原语，不代表用户语义分类。");
            if (!languages.isEmpty()) {
                description.append(" queryLanguageCapabilities=")
                        .append(JSONUtil.toJsonStr(languages)).append("。");
            }
        }

        if (enriched.containsKey("filter") && enriched.containsKey("select")) {
            enriched.computeIfPresent("filter", (key, value) -> value
                    + " 派生值条件必须把 transforms/explode 写在 filter 的 value expression 自身，operator 比较变换后的值；"
                    + "不要只在 select 中变换后再对原字段使用 CONTAINS/STARTS_WITH 近似替代。"
                    + " 对 multi-value 的元素级条件，应使用 explode=true。" );
            enriched.computeIfPresent("select", (key, value) -> value
                    + " 当目标是返回 multi-value 中真正命中的元素时，select 与 filter 应基于同一 field 且 explode=true；"
                    + "select 可返回原始元素，filter 可在同一源元素上使用 transforms，Runtime 会做 element binding，"
                    + "不会把同一实体中的其它未命中元素带入结果。" );
            description.append(" 对派生值过滤执行 transform-before-filter；对同一 explode 多值源执行 element-bound filter/projection。");
        }

        if (enriched.containsKey("aggregate")) {
            enriched.computeIfPresent("aggregate", (key, value) -> value
                    + " Metric 的 description 会声明 dataGrain=LOGICAL_ENTITY 或 SOURCE_RECORD；"
                    + "使用 metric 聚合/分组时必须选择与目标事实相同的数据粒度。"
                    + "COUNT 不带 metric 只统计当前逻辑实体行，不能用它代替物理记录计数。" );
            description.append(" 聚合必须遵守 Metric 声明的数据粒度，禁止把 SOURCE_RECORD 与 LOGICAL_ENTITY 当成同一集合。");
        }

        description.append(" outputType=").append(definition.outputType()).append("。");
        if ("CANDIDATE_ENTITY_ID_SET".equals(definition.outputType())) {
            description.append(" 该能力只接受/产生实体 ID 集合，禁止用于标量数字比较。");
        }
        if ("BOOLEAN_SCALAR".equals(definition.outputType())) {
            description.append(" 该能力产生确定性布尔标量，不产生实体集合。");
        }

        return new CapabilityDefinition(definition.name(), definition.version(), description.toString(),
                enriched, definition.requiredArguments(), definition.outputType(), definition.readOnly(),
                definition.requiredPermissions(), definition.supportedDomains(), definition.requiredKbCapabilities(),
                definition.timeoutMs(), definition.maxRows());
    }

    private boolean isVisible(CapabilityDefinition definition, CapabilityInvocationContext context) {
        for (CapabilityVisibilityPolicy policy : visibilityPolicies) {
            if (!policy.isVisible(definition, context)) return false;
        }
        return true;
    }
}
