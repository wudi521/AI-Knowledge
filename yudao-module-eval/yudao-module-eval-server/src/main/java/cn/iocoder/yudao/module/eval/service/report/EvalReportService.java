package cn.iocoder.yudao.module.eval.service.report;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.eval.controller.admin.task.vo.EvalTaskPageReqVO;
import cn.iocoder.yudao.module.eval.controller.admin.task.vo.EvalTaskRespVO;
import cn.iocoder.yudao.module.eval.controller.admin.task.vo.EvalTaskResultRespVO;
import cn.iocoder.yudao.module.eval.controller.admin.task.vo.EvalTaskRunReqVO;
import cn.iocoder.yudao.module.eval.dal.dataobject.cases.EvalCaseDO;
import cn.iocoder.yudao.module.eval.dal.dataobject.result.EvalResultDO;
import cn.iocoder.yudao.module.eval.dal.dataobject.task.EvalTaskDO;
import cn.iocoder.yudao.module.eval.dal.mysql.cases.EvalCaseMapper;
import cn.iocoder.yudao.module.eval.dal.mysql.result.EvalResultMapper;
import cn.iocoder.yudao.module.eval.dal.mysql.task.EvalTaskMapper;
import cn.iocoder.yudao.module.eval.framework.eval.EvalProperties;
import cn.iocoder.yudao.module.eval.service.runner.EvalRunner;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.module.eval.enums.ErrorCodeConstants.EVAL_TASK_NO_CASE;
import static cn.iocoder.yudao.module.eval.enums.ErrorCodeConstants.EVAL_TASK_NOT_EXISTS;
import static cn.iocoder.yudao.module.eval.enums.ErrorCodeConstants.EVAL_TASK_RUNNING;

/**
 * 评测报表服务: 任务发起 / 任务查询 / 逐题结果查询
 * <p>
 * 聚合职责(任务级 metrics/gate_pass/fail_cases): 执行器(EvalRunner)只负责逐题落库并标记 DONE,
 * 任务级指标快照由本服务在首次查询 DONE 任务时按逐题结果聚合补写(avg 各指标 + gatePass = 全题达标
 * + 失败用例明细), 幂等(metrics 已写入则不重复计算)。
 */
@Slf4j
@Service
public class EvalReportService {

    @Resource
    private EvalTaskMapper evalTaskMapper;
    @Resource
    private EvalResultMapper evalResultMapper;
    @Resource
    private EvalCaseMapper evalCaseMapper;
    @Resource
    private EvalRunner evalRunner;
    @Resource
    private EvalProperties evalProperties;

    /**
     * 创建评测任务并异步执行: 解析用例范围(caseIds 优先 → kbId → 全部), 落 RUNNING 任务,
     * 立即返回 taskId(RPC 由守护线程池异步执行, 不阻塞调用方)
     *
     * @param reqVO 发起参数(选考题 / 知识库; 全空 = 全部用例)
     * @return 评测任务编号
     */
    public Long createAndRun(EvalTaskRunReqVO reqVO) {
        // 1. 解析用例范围: 选考题优先; 否则按 kbId 过滤; 全空 = 全部用例
        List<EvalCaseDO> cases;
        if (CollUtil.isNotEmpty(reqVO.getCaseIds())) {
            cases = evalCaseMapper.selectByIds(reqVO.getCaseIds());
        } else {
            cases = evalCaseMapper.selectList(new LambdaQueryWrapperX<EvalCaseDO>()
                    .eqIfPresent(EvalCaseDO::getKbId, reqVO.getKbId())
                    .orderByAsc(EvalCaseDO::getId));
        }
        if (CollUtil.isEmpty(cases)) {
            throw new ServiceException(EVAL_TASK_NO_CASE);
        }
        // 1.5 可重入防护: 同知识库(或全局, kbId 为空时)已有 RUNNING 任务 → 拒绝新任务
        if (evalTaskMapper.existsRunning(reqVO.getKbId())) {
            throw new ServiceException(EVAL_TASK_RUNNING);
        }
        // 2. 落任务(RUNNING)
        EvalTaskDO task = EvalTaskDO.builder()
                .status(EvalRunner.STATUS_RUNNING)
                .kbId(reqVO.getKbId())
                .caseCount(cases.size())
                .model(evalProperties.getModel())
                .startTime(LocalDateTime.now())
                .build();
        // 选考题模式: 记录考题列表, 执行器按此精确执行(空则按 kbId/全部)
        if (CollUtil.isNotEmpty(reqVO.getCaseIds())) {
            task.setCaseIds(JSONUtil.toJsonStr(reqVO.getCaseIds()));
        }
        evalTaskMapper.insert(task);
        // 3. 异步执行(不阻塞; 异常由执行器内部兜底置 FAILED)
        evalRunner.runTaskAsync(task.getId());
        log.info("[createAndRun][创建评测任务 {}: 用例 {} 条, kbId={}, caseIds={}]",
                task.getId(), cases.size(), reqVO.getKbId(), reqVO.getCaseIds());
        return task.getId();
    }

    /**
     * 获得评测任务(不存在则抛 EVAL_TASK_NOT_EXISTS)
     * <p>
     * DONE 且任务级 metrics 未聚合时, 由逐题结果实时聚合补写并返回(幂等: 已聚合不重复计算)
     */
    public EvalTaskRespVO getTask(Long taskId) {
        EvalTaskDO task = evalTaskMapper.selectById(taskId);
        if (task == null) {
            throw new ServiceException(EVAL_TASK_NOT_EXISTS);
        }
        if (EvalRunner.STATUS_DONE.equals(task.getStatus()) && StrUtil.isBlank(task.getMetrics())) {
            task = aggregateAndPersist(task);
        }
        return toRespVO(task);
    }

    /**
     * 获得评测任务分页
     */
    public PageResult<EvalTaskDO> getTaskPage(EvalTaskPageReqVO pageReqVO) {
        return evalTaskMapper.selectPage(pageReqVO);
    }

    /**
     * 获得评测任务逐题结果(按结果编号升序, 与执行顺序一致; 附带考题问题文案)
     *
     * @param taskId 评测任务编号
     * @return 逐题结果列表(无结果返回空列表)
     */
    public List<EvalTaskResultRespVO> getResults(Long taskId) {
        List<EvalResultDO> results = evalResultMapper.selectListByTaskId(taskId);
        if (CollUtil.isEmpty(results)) {
            return List.of();
        }
        // 批量加载用例(问题文案), 避免 N+1
        Set<Long> caseIds = results.stream().map(EvalResultDO::getCaseId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, EvalCaseDO> caseMap = CollUtil.isEmpty(caseIds) ? Map.of()
                : evalCaseMapper.selectByIds(caseIds).stream()
                        .collect(Collectors.toMap(EvalCaseDO::getId, Function.identity(), (a, b) -> a));
        List<EvalTaskResultRespVO> list = new ArrayList<>(results.size());
        for (EvalResultDO r : results) {
            EvalTaskResultRespVO vo = new EvalTaskResultRespVO();
            vo.setCaseId(r.getCaseId());
            EvalCaseDO evalCase = caseMap.get(r.getCaseId());
            vo.setQuestion(evalCase != null ? evalCase.getQuestion() : null);
            vo.setAnswerable(r.getAnswerable());
            vo.setConfidence(r.getConfidence());
            vo.setRecallAt5(r.getRecallAt5());
            vo.setMrr(r.getMrr());
            vo.setNdcg(r.getNdcg());
            vo.setFaithfulness(r.getFaithfulness());
            vo.setHallucinationRate(r.getHallucinationRate());
            vo.setCitationAccuracy(r.getCitationAccuracy());
            vo.setPassed(r.getPassed());
            vo.setFailReasons(r.getFailReasons());
            vo.setAnswer(r.getAnswer());
            vo.setTraceId(r.getTraceId());
            list.add(vo);
        }
        return list;
    }

    /**
     * 确保 DONE 任务已完成任务级聚合(gatePass/metrics/failCases 可用)
     * <p>
     * 聚合是惰性的(见 {@link #getTask}): 任务 DONE 但从未被查询时 gatePass 为 null。
     * 闸门检查(EvalApiImpl.checkGate)直接读 gatePass 前必须先触发聚合, 否则会误判未达标。
     *
     * @return 聚合后的最新任务行(无逐题结果时返回入参, 不阻断)
     */
    public EvalTaskDO ensureTaskAggregated(EvalTaskDO task) {
        if (EvalRunner.STATUS_DONE.equals(task.getStatus())
                && (task.getGatePass() == null || StrUtil.isBlank(task.getMetrics()))) {
            return aggregateAndPersist(task);
        }
        return task;
    }

    /**
     * EvalTaskDO → EvalTaskRespVO(metrics/failCases JSON 解析为空安全; 不抛异常)
     */
    public EvalTaskRespVO toRespVO(EvalTaskDO task) {
        EvalTaskRespVO respVO = new EvalTaskRespVO();
        respVO.setId(task.getId());
        respVO.setStatus(task.getStatus());
        respVO.setKbId(task.getKbId());
        respVO.setCaseCount(task.getCaseCount());
        respVO.setModel(task.getModel());
        respVO.setStartTime(task.getStartTime());
        respVO.setEndTime(task.getEndTime());
        respVO.setGatePass(task.getGatePass());
        respVO.setMetrics(parseMetrics(task.getMetrics()));
        respVO.setFailCases(parseFailCases(task.getFailCases()));
        respVO.setCreateTime(task.getCreateTime());
        return respVO;
    }

    /**
     * 任务级聚合: 由逐题结果计算指标均值 + 达标数 + 失败明细, 写入 ai_eval_task.metrics/gate_pass/fail_cases
     * <p>
     * gatePass = 全部逐题达标(有结果); metrics = {caseCount, passedCount, passRate, 各指标均值(4 位小数)};
     * failCases = [{caseId, failReasons}, ...](全达标则置空)
     *
     * @return 聚合后的最新任务行(读取失败则返回入参, 不阻断查询)
     */
    private EvalTaskDO aggregateAndPersist(EvalTaskDO task) {
        List<EvalResultDO> results = evalResultMapper.selectListByTaskId(task.getId());
        if (CollUtil.isEmpty(results)) {
            log.warn("[aggregateAndPersist][任务 {} DONE 但无逐题结果, 跳过聚合]", task.getId());
            return task;
        }
        long passedCount = results.stream().filter(r -> Boolean.TRUE.equals(r.getPassed())).count();
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("caseCount", results.size());
        metrics.put("passedCount", passedCount);
        metrics.put("passRate", round4(passedCount * 1.0 / results.size()));
        metrics.put("recallAt5", round4(avg(results, EvalResultDO::getRecallAt5)));
        metrics.put("mrr", round4(avg(results, EvalResultDO::getMrr)));
        metrics.put("ndcg", round4(avg(results, EvalResultDO::getNdcg)));
        metrics.put("faithfulness", round4(avg(results, EvalResultDO::getFaithfulness)));
        metrics.put("hallucinationRate", round4(avg(results, EvalResultDO::getHallucinationRate)));
        metrics.put("citationAccuracy", round4(avg(results, EvalResultDO::getCitationAccuracy)));
        // 失败用例明细(未达标行: caseId + 原因)
        List<Map<String, Object>> failList = new ArrayList<>();
        for (EvalResultDO r : results) {
            if (!Boolean.TRUE.equals(r.getPassed())) {
                Map<String, Object> fail = new LinkedHashMap<>();
                fail.put("caseId", r.getCaseId());
                fail.put("failReasons", r.getFailReasons());
                failList.add(fail);
            }
        }
        EvalTaskDO update = new EvalTaskDO();
        update.setId(task.getId());
        update.setMetrics(JSONUtil.toJsonStr(metrics));
        update.setGatePass(passedCount == results.size() ? 1 : 0);
        update.setFailCases(failList.isEmpty() ? null : JSONUtil.toJsonStr(failList));
        evalTaskMapper.updateById(update);
        log.info("[aggregateAndPersist][任务 {} 聚合完成: 共 {} 题, 达标 {} 题, gatePass={}]",
                task.getId(), results.size(), passedCount, passedCount == results.size());
        EvalTaskDO refreshed = evalTaskMapper.selectById(task.getId());
        return refreshed != null ? refreshed : task;
    }

    /**
     * 指标均值(忽略 null 值; 全部 null 按 0 兜底)
     */
    private double avg(List<EvalResultDO> results, Function<EvalResultDO, Double> getter) {
        double sum = 0;
        int n = 0;
        for (EvalResultDO r : results) {
            Double v = getter.apply(r);
            if (v != null) {
                sum += v;
                n++;
            }
        }
        return n == 0 ? 0.0 : sum / n;
    }

    /**
     * 保留 4 位小数(NaN/Infinity → 0; 对齐 decimal(5,4) 存储精度)
     */
    private double round4(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * metrics JSON → Map(空/脏数据 → null, 不阻断查询)
     */
    private Map<String, Object> parseMetrics(String json) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {
            JSONObject obj = JSONUtil.parseObj(json);
            Map<String, Object> map = new LinkedHashMap<>();
            obj.forEach(map::put);
            return map;
        } catch (Exception e) {
            log.warn("[parseMetrics][解析失败, 按空处理: {}]", json, e);
            return null;
        }
    }

    /**
     * fail_cases JSON → List<Map>(空/脏数据 → null; 对象形态(如 FAILED 兜底 {"error":...}) → 单元素列表)
     */
    private List<Map<String, Object>> parseFailCases(String json) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {
            List<Map<String, Object>> list = new ArrayList<>();
            if (StrUtil.startWith(json.trim(), "[")) {
                JSONArray array = JSONUtil.parseArray(json);
                for (int i = 0; i < array.size(); i++) {
                    Object o = array.get(i);
                    if (o instanceof JSONObject jo) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        jo.forEach(m::put);
                        list.add(m);
                    }
                }
            } else {
                JSONObject obj = JSONUtil.parseObj(json);
                Map<String, Object> m = new LinkedHashMap<>();
                obj.forEach(m::put);
                list.add(m);
            }
            return list;
        } catch (Exception e) {
            log.warn("[parseFailCases][解析失败, 按空处理: {}]", json, e);
            return null;
        }
    }

}
