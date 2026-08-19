package cn.iocoder.yudao.module.eval.service.cases;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.eval.controller.admin.cases.vo.EvalCasePageReqVO;
import cn.iocoder.yudao.module.eval.controller.admin.cases.vo.EvalCaseSaveReqVO;
import cn.iocoder.yudao.module.eval.controller.admin.cases.vo.EvalCaseUpdateReqVO;
import cn.iocoder.yudao.module.eval.dal.dataobject.cases.EvalCaseDO;
import cn.iocoder.yudao.module.eval.dal.mysql.cases.EvalCaseMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.List;

import static cn.iocoder.yudao.module.eval.enums.ErrorCodeConstants.EVAL_CASE_NOT_EXISTS;

/**
 * 评测用例 Service: 考题 CRUD + 反馈生成用例
 */
@Slf4j
@Service
@Validated
public class EvalCaseService {

    @Resource
    private EvalCaseMapper evalCaseMapper;

    /**
     * 创建评测用例
     */
    public Long createCase(EvalCaseSaveReqVO createReqVO) {
        EvalCaseDO evalCase = BeanUtils.toBean(createReqVO, EvalCaseDO.class);
        evalCase.setGoldChunks(toGoldChunksJson(createReqVO.getGoldChunks()));
        evalCaseMapper.insert(evalCase);
        log.info("[createCase][创建评测用例 {}: {}]", evalCase.getId(), evalCase.getQuestion());
        return evalCase.getId();
    }

    /**
     * 更新评测用例
     */
    public void updateCase(EvalCaseUpdateReqVO updateReqVO) {
        // 校验存在
        validateCaseExists(updateReqVO.getId());
        // 更新(仅拷贝非空字段, 避免覆盖未提交字段)
        EvalCaseDO update = BeanUtils.toBean(updateReqVO, EvalCaseDO.class);
        update.setGoldChunks(toGoldChunksJson(updateReqVO.getGoldChunks()));
        evalCaseMapper.updateById(update);
    }

    /**
     * 删除评测用例
     */
    public void deleteCase(Long id) {
        // 校验存在
        validateCaseExists(id);
        evalCaseMapper.deleteById(id);
    }

    /**
     * 获得评测用例(不存在则抛 EVAL_CASE_NOT_EXISTS)
     */
    public EvalCaseDO getCase(Long id) {
        return validateCaseExists(id);
    }

    /**
     * 获得评测用例分页
     */
    public PageResult<EvalCaseDO> getCasePage(EvalCasePageReqVO pageReqVO) {
        return evalCaseMapper.selectPage(pageReqVO);
    }

    /**
     * 反馈转评测用例(chat 模块反馈落库后 RPC 调用; 标准答案/标准证据待人工补充)
     *
     * @param kbId              知识库编号
     * @param question          问题(来自反馈对应的用户消息)
     * @param sourceFeedbackId  来源反馈编号(ai_feedback.id)
     * @return 新用例编号
     */
    public Long createCaseFromFeedback(Long kbId, String question, Long sourceFeedbackId) {
        EvalCaseDO evalCase = EvalCaseDO.builder()
                .question(question)
                .kbId(kbId)
                .sourceFeedback(sourceFeedbackId)
                .category("综合")
                .build();
        evalCaseMapper.insert(evalCase);
        log.info("[createCaseFromFeedback][反馈 {} 生成评测用例 {}]", sourceFeedbackId, evalCase.getId());
        return evalCase.getId();
    }

    private EvalCaseDO validateCaseExists(Long id) {
        EvalCaseDO evalCase = evalCaseMapper.selectById(id);
        if (evalCase == null) {
            throw new ServiceException(EVAL_CASE_NOT_EXISTS);
        }
        return evalCase;
    }

    /**
     * List<Long> -> JSON 数组字符串; 空/null 存 null
     */
    private String toGoldChunksJson(List<Long> goldChunks) {
        if (goldChunks == null || goldChunks.isEmpty()) {
            return null;
        }
        return JSONUtil.toJsonStr(goldChunks);
    }

}
