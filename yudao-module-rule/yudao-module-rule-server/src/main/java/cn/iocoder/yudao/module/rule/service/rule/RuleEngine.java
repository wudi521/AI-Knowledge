package cn.iocoder.yudao.module.rule.service.rule;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.rule.dal.dataobject.rule.AiRuleDO;
import cn.iocoder.yudao.module.rule.dal.mysql.rule.AiRuleMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.kie.api.KieBase;
import org.kie.api.runtime.KieSession;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 规则引擎: 解析启用版本 → 编译(KieBase 30s 缓存) → KieSession 执行 → 收集 RuleResult
 * <p>
 * 降级: 任何异常 → log.warn + 返回空列表(规则忽略, 绝不阻断主链路)
 * <p>
 * 灰度简化说明: gray_tenant_ids 本期仅存储, 解析只取 status=1 全量启用行。
 * 规则为租户自有, gray_tenant_ids 的语义是"跨租户灰度(其他租户也使用本规则)",
 * 属小众场景, 本期 KEEP SIMPLE 不做解析, 后续迭代再支持。
 */
@Slf4j
@Component
public class RuleEngine {

    /** 全量启用状态 */
    private static final int STATUS_ENABLED = 1;

    /** KieBase 缓存 TTL(规则变更 ≤30s 生效) */
    private static final long TTL_MS = 30_000L;

    /** KieBase 缓存条目 */
    private record CacheEntry(KieBase kieBase, long expireAt) {
    }

    @Resource
    private AiRuleMapper aiRuleMapper;
    @Resource
    private RuleCompiler ruleCompiler;

    /** KieBase 缓存: key = tenantId:ruleKey:version */
    private final Map<String, CacheEntry> kieBaseCache = new ConcurrentHashMap<>();

    /**
     * 评估规则: 命中返回全部 RuleResult, 未配置/未命中/异常返回空列表
     *
     * @param ruleKey  业务键
     * @param tenantId 租户编号(可空, 兜底 TenantContextHolder)
     * @param facts    事实 Map(规则条件用 $f["key"] 读取)
     */
    public List<RuleResult> evaluate(String ruleKey, Long tenantId, Map<String, Object> facts) {
        if (tenantId == null) {
            tenantId = TenantContextHolder.getTenantId();
        }
        final Long finalTenantId = tenantId;
        try {
            // 租户过滤: ai_rule 为租户级表, 需在指定租户上下文内查询(Feign 调用可能无租户 header)
            return TenantUtils.execute(tenantId, () -> doEvaluate(ruleKey, finalTenantId, facts));
        } catch (Exception e) {
            log.warn("[evaluate][ruleKey({}) tenantId({}) 规则评估失败, 忽略规则走原链路]", ruleKey, tenantId, e);
            return List.of();
        }
    }

    private List<RuleResult> doEvaluate(String ruleKey, Long tenantId, Map<String, Object> facts) {
        // 1. 解析启用版本(灰度简化: 本期只取 status=1 全量启用行, 按版本倒序取最新)
        List<AiRuleDO> rows = aiRuleMapper.selectByKeyAndStatusIn(ruleKey, List.of(STATUS_ENABLED));
        if (rows.isEmpty()) {
            return List.of(); // 未配置规则 → 走原链路
        }
        AiRuleDO rule = rows.get(0);
        // 2. 编译(带 30s 缓存)
        KieBase kieBase = getKieBase(tenantId, rule.getRuleKey(), rule.getVersion(), rule.getDrlContent());
        // 3. 执行
        return run(kieBase, facts);
    }

    /**
     * 试运行指定规则文本(管理端 validate 用; 编译失败直接抛错, 不降级)
     */
    public List<RuleResult> validate(String ruleKey, String drl, Map<String, Object> facts) {
        KieBase kieBase = ruleCompiler.compile(ruleKey, drl);
        return run(kieBase, facts);
    }

    /**
     * 执行规则: 事实 Map 作为 fact 插入, 规则命中 insert(RuleResult), 执行后收集全部 RuleResult
     */
    private List<RuleResult> run(KieBase kieBase, Map<String, Object> facts) {
        KieSession session = kieBase.newKieSession();
        try {
            if (facts != null) {
                session.insert(facts);
            }
            session.fireAllRules();
            List<RuleResult> results = new ArrayList<>();
            for (Object obj : session.getObjects(o -> o instanceof RuleResult)) {
                results.add((RuleResult) obj);
            }
            return results;
        } finally {
            session.dispose();
        }
    }

    private KieBase getKieBase(Long tenantId, String ruleKey, Integer version, String drl) {
        String cacheKey = tenantId + ":" + ruleKey + ":" + version;
        CacheEntry entry = kieBaseCache.get(cacheKey);
        if (entry != null && entry.expireAt() > System.currentTimeMillis()) {
            return entry.kieBase();
        }
        if (entry != null) {
            kieBaseCache.remove(cacheKey);
        }
        KieBase kieBase = ruleCompiler.compile(ruleKey, drl);
        kieBaseCache.put(cacheKey, new CacheEntry(kieBase, System.currentTimeMillis() + TTL_MS));
        return kieBase;
    }

}
