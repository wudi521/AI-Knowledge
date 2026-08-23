package cn.iocoder.yudao.module.eval.api;

import cn.hutool.core.collection.CollUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.eval.dal.dataobject.task.EvalTaskDO;
import cn.iocoder.yudao.module.eval.dal.mysql.task.EvalTaskMapper;
import cn.iocoder.yudao.module.eval.framework.eval.EvalProperties;
import cn.iocoder.yudao.module.eval.service.cases.EvalCaseService;
import cn.iocoder.yudao.module.eval.service.report.EvalReportService;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import cn.iocoder.yudao.module.knowledge.api.dto.KnowledgePublishedChunkDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 评测平台 对外 RPC 实现
 */
@Slf4j
@RestController
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
    @Resource
    private KnowledgeApi knowledgeApi;

    @Override
    public CommonResult<Long> createCaseFromFeedback(Long kbId, String question, Long sourceFeedbackId) {
        return success(evalCaseService.createCaseFromFeedback(kbId, question, sourceFeedbackId));
    }

    @Override
    public CommonResult<Boolean> checkGate(Long kbId) {
        // 闸门配置关闭 → 恒放行(开发/逐步启用阶段不阻断发布)
        if (!evalProperties.getGate().isEnabled()) {
            return success(true);
        }
        try {
            EvalTaskDO task = evalTaskMapper.selectLatestDoneByKbId(kbId);
            if (task == null) {
                // Bootstrap 规则：首次发布前没有“已发布内容”，因此既无法从正式内容自动生成评测集，
                // 也不应要求一个尚不存在的 DONE 评测结果。允许首次发布建立质量基线；
                // 一旦知识库已有正式内容，后续发布仍必须先完成并通过质量评测。
                List<KnowledgePublishedChunkDTO> published = knowledgeApi.getPublishedChunks(kbId).getCheckedData();
                if (CollUtil.isEmpty(published)) {
                    log.info("[checkGate][知识库 {} 尚无已发布知识, bootstrap 首次发布放行]", kbId);
                    return success(true);
                }
                log.warn("[checkGate][知识库 {} 已有正式内容但无 DONE 评测, 阻断发布]", kbId);
                return success(false);
            }
            // DONE 但 gatePass 尚未聚合 → 先触发聚合，再按闸门判定
            task = evalReportService.ensureTaskAggregated(task);
            return success(Integer.valueOf(1).equals(task.getGatePass()));
        } catch (Exception e) {
            // 除 bootstrap 判定外，内部故障保守按“不通过”返回，避免未验证更新进入正式服务。
            log.error("[checkGate][知识库 {} 闸门检查异常, 保守返回不通过]", kbId, e);
            return success(false);
        }
    }

}
