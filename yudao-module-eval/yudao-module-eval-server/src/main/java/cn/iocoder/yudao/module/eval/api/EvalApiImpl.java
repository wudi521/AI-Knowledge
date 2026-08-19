package cn.iocoder.yudao.module.eval.api;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.eval.dal.dataobject.task.EvalTaskDO;
import cn.iocoder.yudao.module.eval.dal.mysql.task.EvalTaskMapper;
import cn.iocoder.yudao.module.eval.framework.eval.EvalProperties;
import cn.iocoder.yudao.module.eval.service.cases.EvalCaseService;
import cn.iocoder.yudao.module.eval.service.report.EvalReportService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 评测平台 对外 RPC 实现
 */
@Slf4j
@RestController // 提供 RESTful API 接口，给 Feign 调用
@Validated
public class EvalApiImpl implements EvalApi {

    @Resource
    private EvalCaseService evalCaseService;
    @Resource
    private EvalTaskMapper evalTaskMapper;
    @Resource
    private EvalReportService evalReportService;
    @Resource
    private EvalProperties evalProperties;

    @Override
    public CommonResult<Long> createCaseFromFeedback(Long kbId, String question, Long sourceFeedbackId) {
        return success(evalCaseService.createCaseFromFeedback(kbId, question, sourceFeedbackId));
    }

    @Override
    public CommonResult<Boolean> checkGate(Long kbId) {
        // 闸门配置关闭 → 恒放行(测试环境友好, 不阻断发布)
        if (!evalProperties.getGate().isEnabled()) {
            return success(true);
        }
        try {
            // 无 DONE 任务 → 未评测 → 阻断(提示先评测)
            EvalTaskDO task = evalTaskMapper.selectLatestDoneByKbId(kbId);
            if (task == null) {
                return success(false);
            }
            // DONE 但 gatePass 尚未聚合(任务未被报表查询过) → 先触发聚合, 再按闸门判定
            task = evalReportService.ensureTaskAggregated(task);
            return success(Integer.valueOf(1).equals(task.getGatePass()));
        } catch (Exception e) {
            // 实现侧不抛异常: 内部故障保守按"不通过"返回, 由调用方(knowledge)决定是否阻断
            log.error("[checkGate][知识库 {} 闸门检查异常, 保守返回不通过]", kbId, e);
            return success(false);
        }
    }

}
