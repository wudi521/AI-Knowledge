package cn.iocoder.yudao.module.eval.service.runner;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.eval.dal.dataobject.cases.EvalCaseDO;
import cn.iocoder.yudao.module.eval.dal.dataobject.result.EvalResultDO;
import cn.iocoder.yudao.module.eval.dal.dataobject.task.EvalTaskDO;
import cn.iocoder.yudao.module.eval.dal.mysql.cases.EvalCaseMapper;
import cn.iocoder.yudao.module.eval.dal.mysql.result.EvalResultMapper;
import cn.iocoder.yudao.module.eval.dal.mysql.task.EvalTaskMapper;
import cn.iocoder.yudao.module.eval.service.metric.EvalMetricService;
import cn.iocoder.yudao.module.evidence.api.EvidenceApi;
import cn.iocoder.yudao.module.evidence.api.dto.EvidenceEvaluateReqDTO;
import cn.iocoder.yudao.module.evidence.api.dto.EvidenceEvaluateRespDTO;
import cn.iocoder.yudao.module.evidence.api.dto.EvidenceItemDTO;
import com.alibaba.ttl.TtlRunnable;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 评测执行器: 逐题调用 evidence.evaluate, 原始结果落库 ai_eval_result
 * <p>
 * 设计决策:
 * 1. 顺序执行(单线程守护线程池): 保证同一任务内逐题确定性, 便于复现排查; 后续如需提速, 可基于
 * {@code yudao.eval.runner.max-parallel} 配置(默认 2)改造为信号量限流的并行执行;
 * 2. 失败隔离: 单题 RPC 异常仅将该题标记未通过(评估异常), 不中断后续用例;
 * 3. 异步非阻塞: {@link #runTaskAsync} 立即返回, 由守护线程池执行, 不阻塞 HTTP 调用方;
 * 4. 租户: 异步线程无租户上下文, 先忽略租户读取任务行取得 tenant_id, 再以该租户执行
 * (TtlRunnable 透传调度线程租户作为兜底);
 * 5. 指标(Recall/MRR/NDCG/忠实度等)由 MetricCalculator(任务 4)基于 {@code resultChunks}/{@code claims} 与
 * 标准证据计算: 本执行器仅持久化原始数据(检索结果顺序 + 断言验证结果), 全部用例落库后调用
 * {@link EvalMetricService#fillMetrics} 回填指标并判定逐题达标, 再标记 DONE;
 * 任务级 metrics/gate_pass/fail_cases 由任务 5 汇总填充。
 */
@Slf4j
@Component
public class EvalRunner {

    /** 状态: 运行中 */
    public static final String STATUS_RUNNING = "RUNNING";
    /** 状态: 完成(逐题结果均已落库, 含单题失败) */
    public static final String STATUS_DONE = "DONE";
    /** 状态: 失败(任务级异常) */
    public static final String STATUS_FAILED = "FAILED";

    /** 证据条数 topK(与证据侧默认一致) */
    private static final int TOP_K = 8;

    /** fail_reasons / fail_cases 字段内容上限(varchar(500)) */
    private static final int MAX_FAIL_REASON_LEN = 500;

    /**
     * 评测专用线程池(守护线程, 不阻止 JVM 退出; 单线程 = 逐题顺序执行, 见类注释)
     */
    private static final ExecutorService TASK_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "eval-runner");
        t.setDaemon(true);
        return t;
    });

    @Resource
    private EvidenceApi evidenceApi;
    @Resource
    private EvalCaseMapper evalCaseMapper;
    @Resource
    private EvalResultMapper evalResultMapper;
    @Resource
    private EvalTaskMapper evalTaskMapper;
    @Resource
    private EvalMetricService evalMetricService;

    /**
     * 异步执行评测任务(非阻塞): 立即返回, 由守护线程逐题执行并落库;
     * 内部全链路 try/catch, 任何异常都不会抛出到调用方(任务置 FAILED)
     *
     * @param taskId 评测任务编号
     */
    public void runTaskAsync(Long taskId) {
        // 捕获调度线程(HTTP 请求)租户: TtlRunnable 透传, 作为异步线程租户兜底
        Long callerTenantId = TenantContextHolder.getTenantId();
        Runnable task = () -> {
            try {
                // 异步线程无租户上下文: 以任务行自身 tenant_id 为准(更健壮, 不依赖调度线程)
                EvalTaskDO taskDO = selectTaskIgnoreTenant(taskId);
                Long tenantId = taskDO != null && taskDO.getTenantId() != null
                        ? taskDO.getTenantId() : callerTenantId;
                TenantUtils.execute(tenantId, () -> runTask(taskId));
            } catch (Exception e) {
                // 兜底: 标记 FAILED(租户未知时忽略租户写入, 保证状态可追踪)
                log.error("[runTaskAsync][评测任务 {} 执行异常, 标记 FAILED]", taskId, e);
                try {
                    TenantUtils.executeIgnore(() -> markTaskFailed(taskId, e.getMessage()));
                } catch (Exception ex) {
                    log.error("[runTaskAsync][评测任务 {} 标记 FAILED 失败]", taskId, ex);
                }
            }
        };
        TASK_EXECUTOR.execute(TtlRunnable.get(task)); // 透传调度线程租户上下文
    }

    /**
     * 同步执行评测任务(调用方需保证租户上下文正确; 推荐入口为 {@link #runTaskAsync})
     * <p>
     * 流程: 加载任务 → 按 kbId 加载用例 → 记录开始时间/caseCount → 逐题 evidence.evaluate 落库 →
     * 收尾(结束时间 + DONE)。约束: 永不抛出(异常置任务 FAILED); 单题失败隔离不中断后续。
     *
     * @param taskId 评测任务编号(不存在则仅告警返回)
     */
    public void runTask(Long taskId) {
        try {
            runTaskInternal(taskId);
        } catch (Exception e) {
            // 外层兜底: 满足"runTask 永不抛出"约束
            log.error("[runTask][评测任务 {} 执行异常, 标记 FAILED]", taskId, e);
            try {
                markTaskFailed(taskId, e.getMessage());
            } catch (Exception ex) {
                log.error("[runTask][评测任务 {} 标记 FAILED 失败]", taskId, ex);
            }
        }
    }

    private void runTaskInternal(Long taskId) {
        EvalTaskDO task = evalTaskMapper.selectById(taskId);
        if (task == null) {
            log.warn("[runTask][评测任务 {} 不存在, 忽略]", taskId);
            return;
        }
        // 1. 加载用例: task.kbId 为空 → 全部用例; 非空 → 限定该知识库
        List<EvalCaseDO> cases = evalCaseMapper.selectList(new LambdaQueryWrapperX<EvalCaseDO>()
                .eqIfPresent(EvalCaseDO::getKbId, task.getKbId())
                .orderByAsc(EvalCaseDO::getId));
        // 2. 记录开始时间与考题数
        long startTime = System.currentTimeMillis();
        EvalTaskDO start = new EvalTaskDO();
        start.setId(taskId);
        start.setStatus(STATUS_RUNNING);
        start.setCaseCount(cases.size());
        start.setStartTime(LocalDateTime.now());
        evalTaskMapper.updateById(start);
        log.info("[runTask][任务 {} 开始评测: 用例 {} 条, kbId={}]", taskId, cases.size(), task.getKbId());
        // 3. 逐题评估(单题失败隔离, 不影响后续用例); 达标与否由指标回填(步骤 4)判定
        for (EvalCaseDO evalCase : cases) {
            EvalResultDO result = evaluateOne(evalCase, task);
            evalResultMapper.insert(result);
            log.info("[runTask][任务 {} 用例 {} 评测完成: answerable={}, confidence={}]",
                    taskId, evalCase.getId(), result.getAnswerable(), result.getConfidence());
        }
        // 4. 指标回填: 逐题计算 Recall/MRR/NDCG/忠实度/幻觉率/引用准确率 + 达标判定
        //    (失败不影响 DONE: 逐题原始结果已落库, 指标可后续补跑)
        try {
            evalMetricService.fillMetrics(taskId);
        } catch (Exception e) {
            log.error("[runTask][任务 {} 指标回填失败, 继续收尾]", taskId, e);
        }
        // 5. 收尾: 结束时间 + DONE(任务级 metrics/gate_pass/fail_cases 由任务 5 汇总填充)
        EvalTaskDO finish = new EvalTaskDO();
        finish.setId(taskId);
        finish.setStatus(STATUS_DONE);
        finish.setEndTime(LocalDateTime.now());
        evalTaskMapper.updateById(finish);
        // 达标数在指标回填后统计(逐题 passed 由 fillMetrics 写入)
        long passedCount = evalResultMapper.selectListByTaskId(taskId).stream()
                .filter(r -> Boolean.TRUE.equals(r.getPassed())).count();
        log.info("[runTask][任务 {} 评测完成: 共 {} 题, 达标 {} 题, 耗时 {}ms]",
                taskId, cases.size(), passedCount, System.currentTimeMillis() - startTime);
    }

    /**
     * 单题评估: 调用 evidence.evaluate, 落原始数据; 任何失败仅标记该题未通过(评估异常), 不抛出
     */
    private EvalResultDO evaluateOne(EvalCaseDO evalCase, EvalTaskDO task) {
        EvalResultDO result = EvalResultDO.builder()
                .taskId(task.getId())
                .caseId(evalCase.getId())
                .answerable(false)
                .passed(false)
                .build();
        try {
            // 组装 RPC 请求: 用例 kbId 非空则限定该库, 否则全部可见知识库(证据侧自行降级)
            EvidenceEvaluateReqDTO req = new EvidenceEvaluateReqDTO();
            req.setQuery(evalCase.getQuestion());
            req.setKbIds(evalCase.getKbId() != null ? List.of(evalCase.getKbId()) : null);
            req.setTopK(TOP_K);
            req.setTenantId(task.getTenantId() != null ? task.getTenantId() : TenantContextHolder.getTenantId());
            CommonResult<EvidenceEvaluateRespDTO> resp = evidenceApi.evaluate(req);
            // RPC 失败(网络异常由 catch 处理; 非 0 码/空数据在此处理)
            if (resp == null || resp.getCode() != 0 || resp.getData() == null) {
                String detail = resp != null ? StrUtil.format("code({}) msg({})", resp.getCode(), resp.getMsg()) : "RPC 无响应";
                log.warn("[evaluateOne][任务 {} 用例 {} 评估失败: {}]", task.getId(), evalCase.getId(), detail);
                result.setFailReasons(truncate("评估异常: " + detail));
                return result;
            }
            EvidenceEvaluateRespDTO data = resp.getData();
            result.setAnswerable(Boolean.TRUE.equals(data.getAnswerable()));
            result.setConfidence(data.getConfidence());
            result.setAnswer(data.getAnswer());
            result.setTraceId(data.getTraceId());
            // 检索结果顺序: evidence[] 按得分降序 → chunkId 有序列表(供 MetricCalculator 计算 Recall/MRR/NDCG)
            if (CollUtil.isNotEmpty(data.getEvidence())) {
                List<Long> orderedChunkIds = data.getEvidence().stream()
                        .map(EvidenceItemDTO::getChunkId).toList();
                result.setResultChunks(JSONUtil.toJsonStr(orderedChunkIds));
            }
            // 断言验证结果: [{text,verdict,evidenceIndex}](evidenceIndex 对应 evidence[] 位置 = resultChunks 位置,
            // 供 MetricCalculator 计算忠实度/幻觉率/引用准确率)
            if (CollUtil.isNotEmpty(data.getClaims())) {
                result.setClaims(JSONUtil.toJsonStr(data.getClaims()));
            }
            return result;
        } catch (Exception e) {
            // 单题失败隔离: 记录原因, 不影响后续用例
            log.error("[evaluateOne][任务 {} 用例 {} 评估异常]", task.getId(), evalCase.getId(), e);
            result.setFailReasons(truncate("评估异常: " + e.getMessage()));
            return result;
        }
    }

    /**
     * 忽略租户读取任务行(异步线程无租户上下文, 需先取得任务所属租户)
     */
    private EvalTaskDO selectTaskIgnoreTenant(Long taskId) {
        AtomicReference<EvalTaskDO> ref = new AtomicReference<>();
        TenantUtils.executeIgnore(() -> ref.set(evalTaskMapper.selectById(taskId)));
        return ref.get();
    }

    /**
     * 任务标记 FAILED(调用方需保证租户上下文; 异步兜底场景请包一层 executeIgnore)
     */
    private void markTaskFailed(Long taskId, String errorMsg) {
        EvalTaskDO update = new EvalTaskDO();
        update.setId(taskId);
        update.setStatus(STATUS_FAILED);
        update.setEndTime(LocalDateTime.now());
        update.setFailCases(JSONUtil.toJsonStr(
                JSONUtil.createObj().set("error", truncate(errorMsg))));
        evalTaskMapper.updateById(update);
        log.warn("[markTaskFailed][评测任务 {} 已标记 FAILED: {}]", taskId, truncate(errorMsg));
    }

    /**
     * 截断到 fail_reasons/fail_cases 字段长度上限
     */
    private String truncate(String msg) {
        if (msg == null) {
            return null;
        }
        return msg.length() > MAX_FAIL_REASON_LEN ? msg.substring(0, MAX_FAIL_REASON_LEN) : msg;
    }

}
