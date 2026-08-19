package cn.iocoder.yudao.module.eval.service.metric;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.eval.dal.dataobject.cases.EvalCaseDO;
import cn.iocoder.yudao.module.eval.dal.dataobject.result.EvalResultDO;
import cn.iocoder.yudao.module.eval.dal.mysql.cases.EvalCaseMapper;
import cn.iocoder.yudao.module.eval.dal.mysql.result.EvalResultMapper;
import cn.iocoder.yudao.module.eval.framework.eval.EvalProperties;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 指标回填服务: 任务执行完成后, 逐题基于标准证据/检索结果/断言验证结果计算指标并回填
 * <p>
 * 由 {@code EvalRunner} 在标记 DONE 前调用(同一租户上下文内); 纯计算逻辑见 {@link MetricCalculator}。
 */
@Slf4j
@Service
public class EvalMetricService {

    @Resource
    private EvalProperties evalProperties;
    @Resource
    private EvalResultMapper evalResultMapper;
    @Resource
    private EvalCaseMapper evalCaseMapper;

    /**
     * 回填某任务下全部逐题结果的指标(recall_at_5/mrr/ndcg/faithfulness/hallucination_rate/
     * citation_accuracy/passed/fail_reasons)
     *
     * @param taskId 评测任务编号
     */
    public void fillMetrics(Long taskId) {
        List<EvalResultDO> results = evalResultMapper.selectListByTaskId(taskId);
        if (CollUtil.isEmpty(results)) {
            return;
        }
        // 批量加载用例(标准证据)
        Set<Long> caseIds = results.stream().map(EvalResultDO::getCaseId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<Long, EvalCaseDO> caseMap = CollUtil.isEmpty(caseIds) ? Map.of()
                : evalCaseMapper.selectByIds(caseIds).stream()
                        .collect(Collectors.toMap(EvalCaseDO::getId, Function.identity(), (a, b) -> a));
        // 逐题计算并回填
        for (EvalResultDO result : results) {
            // 执行器已记录失败原因(如评估异常)的行: 无有效检索/断言数据, 指标无意义, 保留诊断信息不覆盖
            if (StrUtil.isNotBlank(result.getFailReasons())) {
                continue;
            }
            EvalCaseDO evalCase = caseMap.get(result.getCaseId());
            List<Long> goldChunks = parseLongList(evalCase != null ? evalCase.getGoldChunks() : null);
            List<Long> resultChunks = parseLongList(result.getResultChunks());
            List<MetricCalculator.ClaimRecord> claims = parseClaims(result.getClaims());
            MetricCalculator.MetricResult metric = MetricCalculator.compute(
                    goldChunks, resultChunks, claims, evalProperties.getGate());
            // 局部更新(updateById 仅更新非空字段)
            EvalResultDO update = new EvalResultDO();
            update.setId(result.getId());
            update.setRecallAt5(metric.recallAt5().doubleValue());
            update.setMrr(metric.mrr().doubleValue());
            update.setNdcg(metric.ndcg().doubleValue());
            update.setFaithfulness(metric.faithfulness().doubleValue());
            update.setHallucinationRate(metric.hallucinationRate().doubleValue());
            update.setCitationAccuracy(metric.citationAccuracy().doubleValue());
            update.setPassed(metric.passed());
            update.setFailReasons(metric.failReasons());
            evalResultMapper.updateById(update);
        }
        log.info("[fillMetrics][任务 {} 指标回填完成: 共 {} 行]", taskId, results.size());
    }

    /**
     * 解析 JSON 数组字符串为 Long 列表(空/非法 → 空列表, 防御性)
     */
    static List<Long> parseLongList(String json) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        try {
            List<Long> list = JSONUtil.toList(json, Long.class);
            return list == null ? List.of() : list;
        } catch (Exception e) {
            log.warn("[parseLongList][解析失败, 按空处理: {}]", json, e);
            return List.of();
        }
    }

    /**
     * 解析 claims JSON([{text,verdict,evidenceIndex}, ...]) 为 {@link MetricCalculator.ClaimRecord} 列表
     * (空/非法 → 空列表, 防御性)
     */
    static List<MetricCalculator.ClaimRecord> parseClaims(String json) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        try {
            JSONArray array = JSONUtil.parseArray(json);
            List<MetricCalculator.ClaimRecord> claims = new ArrayList<>(array.size());
            for (int i = 0; i < array.size(); i++) {
                Object obj = array.get(i);
                if (!(obj instanceof JSONObject jsonObject)) {
                    continue; // 非对象条目跳过
                }
                claims.add(new MetricCalculator.ClaimRecord(
                        jsonObject.getStr("verdict"), jsonObject.getInt("evidenceIndex")));
            }
            return claims;
        } catch (Exception e) {
            log.warn("[parseClaims][解析失败, 按空处理: {}]", json, e);
            return List.of();
        }
    }

}
