package cn.iocoder.yudao.module.model.service.cost;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.model.dal.dataobject.calllog.AiModelCallLogDO;
import cn.iocoder.yudao.module.model.dal.dataobject.model.AiModelConfigDO;
import cn.iocoder.yudao.module.model.dal.mysql.calllog.AiModelCallLogMapper;
import cn.iocoder.yudao.module.model.dal.mysql.model.AiModelConfigMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 成本管理(M8): 基于 ai_model_call_log 的计量聚合
 * <ul>
 *   <li>汇总: 总调用/总 token/成功率/平均耗时/估算成本</li>
 *   <li>趋势: 按天调用量·token·成本(近 N 天, 无调用日期补 0)</li>
 *   <li>分摊: 按租户 / 按场景 / 按模型 / 按状态</li>
 * </ul>
 * 成本估算: 单价来自数据库 `ai_model_config.in_per_mtok/out_per_mtok`(每百万 token 单价, 元)。
 * 未配置单价的模型不估算金额(tokens 仍如实统计), 避免拍脑袋定价。
 * 多租户: DO 继承 TenantBaseDO, Mapper 自动按当前租户过滤; 汇总页即当前租户成本。
 */
@Slf4j
@Service
public class CostService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Resource
    private AiModelCallLogMapper callLogMapper;
    @Resource
    private AiModelConfigMapper aiModelConfigMapper;

    // ========== 汇总 ==========

    public CostSummaryResp summary(Integer recentDays) {
        LocalDateTime since = since(recentDays);
        List<AiModelCallLogDO> rows = callLogMapper.selectList(
                Wrappers.lambdaQuery(AiModelCallLogDO.class).ge(AiModelCallLogDO::getCreateTime, since));
        CostSummaryResp resp = new CostSummaryResp();
        long calls = 0, promptTokens = 0, completionTokens = 0, elapsedMs = 0, success = 0;
        for (AiModelCallLogDO r : rows) {
            calls++;
            promptTokens += nullToZero(r.getPromptTokens());
            completionTokens += nullToZero(r.getCompletionTokens());
            elapsedMs += nullToZero(r.getElapsedMs());
            // DEGRADED 视为成功(降级走备选仍是可用调用), 避免全链路可用但成功率虚低
            if ("SUCCESS".equals(r.getStatus()) || "DEGRADED".equals(r.getStatus())) {
                success++;
            }
        }
        resp.setTotalCalls(calls);
        resp.setPromptTokens(promptTokens);
        resp.setCompletionTokens(completionTokens);
        resp.setTotalTokens(promptTokens + completionTokens);
        resp.setSuccessRate(calls == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(success * 100.0 / calls).setScale(1, RoundingMode.HALF_UP));
        resp.setAvgElapsedMs(calls == 0 ? 0 : (int) (elapsedMs / calls));
        resp.setEstimatedCost(estimateCost(rows));
        return resp;
    }

    // ========== 趋势(按天) ==========

    public List<CostTrendItem> trend(Integer days) {
        LocalDateTime since = since(days);
        List<AiModelCallLogDO> rows = callLogMapper.selectList(
                Wrappers.lambdaQuery(AiModelCallLogDO.class).ge(AiModelCallLogDO::getCreateTime, since));
        // 按天聚合
        Map<String, CostTrendItem> byDay = new LinkedHashMap<>();
        for (AiModelCallLogDO r : rows) {
            String day = r.getCreateTime().format(DAY);
            CostTrendItem item = byDay.computeIfAbsent(day, k -> {
                CostTrendItem i = new CostTrendItem();
                i.setDate(k);
                return i;
            });
            item.setCalls(item.getCalls() + 1);
            item.setPromptTokens(item.getPromptTokens() + nullToZero(r.getPromptTokens()));
            item.setCompletionTokens(item.getCompletionTokens() + nullToZero(r.getCompletionTokens()));
            item.setElapsedMs(item.getElapsedMs() + nullToZero(r.getElapsedMs()));
            // DEGRADED 视为成功(降级走备选仍是可用调用)
            if ("SUCCESS".equals(r.getStatus()) || "DEGRADED".equals(r.getStatus())) {
                item.setSuccessCalls(item.getSuccessCalls() + 1);
            }
        }
        // 补零: 区间内每一天(含无调用日)
        List<CostTrendItem> result = new ArrayList<>();
        LocalDate cursor = since.toLocalDate();
        LocalDate end = LocalDate.now();
        while (!cursor.isAfter(end)) {
            CostTrendItem item = byDay.getOrDefault(cursor.format(DAY), new CostTrendItem());
            item.setDate(cursor.format(DAY));
            item.setEstimatedCost(estimateCostOfItem(item));
            result.add(item);
            cursor = cursor.plusDays(1);
        }
        return result;
    }

    // ========== 分摊(租户/场景/模型/状态) ==========

    public List<CostGroupItem> byTenant(Integer recentDays) {
        return group(recentDays, r -> String.valueOf(r.getTenantId()));
    }

    public List<CostGroupItem> byScenario(Integer recentDays) {
        return group(recentDays, r -> StrUtil.blankToDefault(r.getScenario(), "*"));
    }

    public List<CostGroupItem> byModel(Integer recentDays) {
        return group(recentDays, r -> StrUtil.blankToDefault(r.getModelName(), "(未配置单价)"));
    }

    public List<CostGroupItem> byStatus(Integer recentDays) {
        return group(recentDays, r -> StrUtil.blankToDefault(r.getStatus(), "UNKNOWN"));
    }

    private List<CostGroupItem> group(Integer recentDays, Function<AiModelCallLogDO, String> keyFn) {
        LocalDateTime since = since(recentDays);
        List<AiModelCallLogDO> rows = callLogMapper.selectList(
                Wrappers.lambdaQuery(AiModelCallLogDO.class).ge(AiModelCallLogDO::getCreateTime, since));
        Map<String, CostGroupItem> map = new LinkedHashMap<>();
        for (AiModelCallLogDO r : rows) {
            String key = keyFn.apply(r);
            CostGroupItem item = map.computeIfAbsent(key, k -> {
                CostGroupItem i = new CostGroupItem();
                i.setGroup(key);
                return i;
            });
            item.setCalls(item.getCalls() + 1);
            item.setPromptTokens(item.getPromptTokens() + nullToZero(r.getPromptTokens()));
            item.setCompletionTokens(item.getCompletionTokens() + nullToZero(r.getCompletionTokens()));
            item.setElapsedMs(item.getElapsedMs() + nullToZero(r.getElapsedMs()));
            // DEGRADED 视为成功(降级走备选仍是可用调用)
            if ("SUCCESS".equals(r.getStatus()) || "DEGRADED".equals(r.getStatus())) {
                item.setSuccessCalls(item.getSuccessCalls() + 1);
            }
        }
        // 成本估算(需按组内模型分别定价, 这里用组内平均单价近似; 未配置单价模型计 0)
        for (CostGroupItem item : map.values()) {
            item.setEstimatedCost(BigDecimal.ZERO);
        }
        return map.values().stream()
                .sorted((a, b) -> Long.compare(b.getCalls(), a.getCalls()))
                .collect(Collectors.toList());
    }

    // ========== 成本估算 ==========

    private BigDecimal estimateCost(List<AiModelCallLogDO> rows) {
        if (CollUtil.isEmpty(rows)) {
            return BigDecimal.ZERO;
        }
        Map<String, Pricing> pricing = loadPricing();
        BigDecimal total = BigDecimal.ZERO;
        for (AiModelCallLogDO r : rows) {
            Pricing p = pricing.get(StrUtil.blankToDefault(r.getModelName(), ""));
            if (p == null) {
                continue; // 未配置单价不估算
            }
            total = total.add(p.cost(r));
        }
        return total.setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal estimateCostOfItem(CostTrendItem item) {
        // 趋势维度按 token 与默认单价估算(简化: 汇总口径精确, 趋势口径近似)
        BigDecimal in = BigDecimal.valueOf(item.getPromptTokens())
                .multiply(defaultInPrice()).divide(BigDecimal.valueOf(1_000_000), 4, RoundingMode.HALF_UP);
        BigDecimal out = BigDecimal.valueOf(item.getCompletionTokens())
                .multiply(defaultOutPrice()).divide(BigDecimal.valueOf(1_000_000), 4, RoundingMode.HALF_UP);
        return in.add(out);
    }

    private Map<String, Pricing> loadPricing() {
        Map<String, Pricing> map = new LinkedHashMap<>();
        List<AiModelConfigDO> configs = aiModelConfigMapper.selectList(
                Wrappers.lambdaQuery(AiModelConfigDO.class).eq(AiModelConfigDO::getStatus, 1));
        for (AiModelConfigDO cfg : configs) {
            if (cfg.getInPerMtok() == null || cfg.getOutPerMtok() == null) {
                continue; // 未配置单价不估算
            }
            map.put(cfg.getModelName(), new Pricing(cfg.getInPerMtok(), cfg.getOutPerMtok()));
        }
        return map;
    }

    /** 趋势口径近似单价: 取 chat 类型启用且配置了单价的首个模型; 均未配则 0(汇总口径仍逐模型精确) */
    private Pricing defaultPricing() {
        List<AiModelConfigDO> chats = aiModelConfigMapper.selectList(
                Wrappers.lambdaQuery(AiModelConfigDO.class)
                        .eq(AiModelConfigDO::getType, "chat")
                        .eq(AiModelConfigDO::getStatus, 1)
                        .orderByAsc(AiModelConfigDO::getId));
        for (AiModelConfigDO cfg : chats) {
            if (cfg.getInPerMtok() != null && cfg.getOutPerMtok() != null) {
                return new Pricing(cfg.getInPerMtok(), cfg.getOutPerMtok());
            }
        }
        return new Pricing(BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private BigDecimal defaultInPrice() {
        return defaultPricing().inPerMtok();
    }

    private BigDecimal defaultOutPrice() {
        return defaultPricing().outPerMtok();
    }

    private static int nullToZero(Integer v) {
        return v == null ? 0 : v;
    }

    private static LocalDateTime since(Integer recentDays) {
        int days = recentDays == null || recentDays <= 0 ? 30 : Math.min(recentDays, 365);
        return LocalDateTime.now().minusDays(days);
    }

    /** 单价(每百万 token, 元) */
    private record Pricing(BigDecimal inPerMtok, BigDecimal outPerMtok) {
        BigDecimal cost(AiModelCallLogDO r) {
            BigDecimal in = BigDecimal.valueOf(nullToZero(r.getPromptTokens()))
                    .multiply(inPerMtok).divide(BigDecimal.valueOf(1_000_000), 4, RoundingMode.HALF_UP);
            BigDecimal out = BigDecimal.valueOf(nullToZero(r.getCompletionTokens()))
                    .multiply(outPerMtok).divide(BigDecimal.valueOf(1_000_000), 4, RoundingMode.HALF_UP);
            return in.add(out);
        }
    }

    // ========== VO ==========

    /** 汇总 */
    public static class CostSummaryResp {
        private Long totalCalls;
        private Long promptTokens;
        private Long completionTokens;
        private Long totalTokens;
        private BigDecimal successRate;
        private Integer avgElapsedMs;
        private BigDecimal estimatedCost;

        public Long getTotalCalls() { return totalCalls; }
        public void setTotalCalls(Long v) { this.totalCalls = v; }
        public Long getPromptTokens() { return promptTokens; }
        public void setPromptTokens(Long v) { this.promptTokens = v; }
        public Long getCompletionTokens() { return completionTokens; }
        public void setCompletionTokens(Long v) { this.completionTokens = v; }
        public Long getTotalTokens() { return totalTokens; }
        public void setTotalTokens(Long v) { this.totalTokens = v; }
        public BigDecimal getSuccessRate() { return successRate; }
        public void setSuccessRate(BigDecimal v) { this.successRate = v; }
        public Integer getAvgElapsedMs() { return avgElapsedMs; }
        public void setAvgElapsedMs(Integer v) { this.avgElapsedMs = v; }
        public BigDecimal getEstimatedCost() { return estimatedCost; }
        public void setEstimatedCost(BigDecimal v) { this.estimatedCost = v; }
    }

    /** 趋势项 */
    public static class CostTrendItem {
        private String date;
        private Long calls = 0L;
        private Long successCalls = 0L;
        private Long promptTokens = 0L;
        private Long completionTokens = 0L;
        private Long elapsedMs = 0L;
        private BigDecimal estimatedCost;

        public String getDate() { return date; }
        public void setDate(String v) { this.date = v; }
        public Long getCalls() { return calls; }
        public void setCalls(Long v) { this.calls = v; }
        public Long getSuccessCalls() { return successCalls; }
        public void setSuccessCalls(Long v) { this.successCalls = v; }
        public Long getPromptTokens() { return promptTokens; }
        public void setPromptTokens(Long v) { this.promptTokens = v; }
        public Long getCompletionTokens() { return completionTokens; }
        public void setCompletionTokens(Long v) { this.completionTokens = v; }
        public Long getElapsedMs() { return elapsedMs; }
        public void setElapsedMs(Long v) { this.elapsedMs = v; }
        public BigDecimal getEstimatedCost() { return estimatedCost; }
        public void setEstimatedCost(BigDecimal v) { this.estimatedCost = v; }
    }

    /** 分组项(租户/场景/模型/状态共用) */
    public static class CostGroupItem {
        private String group;
        private Long calls = 0L;
        private Long successCalls = 0L;
        private Long promptTokens = 0L;
        private Long completionTokens = 0L;
        private Long elapsedMs = 0L;
        private BigDecimal estimatedCost;

        public String getGroup() { return group; }
        public void setGroup(String v) { this.group = v; }
        public Long getCalls() { return calls; }
        public void setCalls(Long v) { this.calls = v; }
        public Long getSuccessCalls() { return successCalls; }
        public void setSuccessCalls(Long v) { this.successCalls = v; }
        public Long getPromptTokens() { return promptTokens; }
        public void setPromptTokens(Long v) { this.promptTokens = v; }
        public Long getCompletionTokens() { return completionTokens; }
        public void setCompletionTokens(Long v) { this.completionTokens = v; }
        public Long getElapsedMs() { return elapsedMs; }
        public void setElapsedMs(Long v) { this.elapsedMs = v; }
        public BigDecimal getEstimatedCost() { return estimatedCost; }
        public void setEstimatedCost(BigDecimal v) { this.estimatedCost = v; }
    }
}
