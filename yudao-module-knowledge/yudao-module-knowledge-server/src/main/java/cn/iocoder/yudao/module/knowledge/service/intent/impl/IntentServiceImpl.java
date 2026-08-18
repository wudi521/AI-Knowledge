package cn.iocoder.yudao.module.knowledge.service.intent.impl;

import cn.hutool.core.collection.CollUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.knowledge.controller.admin.intent.vo.IntentSaveReqVO;
import cn.iocoder.yudao.module.knowledge.controller.admin.intent.vo.IntentUpdateReqVO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.intent.AiIntentDO;
import cn.iocoder.yudao.module.knowledge.dal.mysql.intent.AiIntentMapper;
import cn.iocoder.yudao.module.knowledge.dal.mysql.knowledge.AiKnowledgeBaseMapper;
import cn.iocoder.yudao.module.knowledge.service.intent.IntentService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.List;

import static cn.iocoder.yudao.module.knowledge.enums.ErrorCodeConstants.*;

/**
 * AI 意图 Service 实现
 */
@Service
@Validated
public class IntentServiceImpl implements IntentService {

    @Resource
    private AiIntentMapper aiIntentMapper;

    @Resource
    private AiKnowledgeBaseMapper aiKnowledgeBaseMapper;

    @Override
    public List<AiIntentDO> listByKb(Long kbId) {
        return aiIntentMapper.selectListByKbId(kbId);
    }

    @Override
    public List<AiIntentDO> listEnabledByKb(Long kbId) {
        return aiIntentMapper.selectEnabledByKbId(kbId);
    }

    @Override
    public Long createIntent(IntentSaveReqVO createReqVO) {
        // 校验知识库存在
        if (aiKnowledgeBaseMapper.selectById(createReqVO.getKbId()) == null) {
            throw new ServiceException(INTENT_KB_NOT_EXISTS);
        }
        AiIntentDO intent = BeanUtils.toBean(createReqVO, AiIntentDO.class);
        intent.setId(null);
        intent.setSource("MANUAL"); // 手动新增固定 MANUAL; LLM_AUTO 仅由意图总结器(T2)写入
        intent.setStatus(0); // 默认启用
        aiIntentMapper.insert(intent);
        return intent.getId();
    }

    @Override
    public void updateIntent(IntentUpdateReqVO updateReqVO) {
        // 校验意图存在
        if (aiIntentMapper.selectById(updateReqVO.getId()) == null) {
            throw new ServiceException(INTENT_NOT_EXISTS);
        }
        AiIntentDO update = BeanUtils.toBean(updateReqVO, AiIntentDO.class);
        aiIntentMapper.updateById(update); // 空字段不更新(MP 默认 NOT_NULL 策略), 支持部分更新
    }

    @Override
    public void deleteIntent(Long id) {
        if (aiIntentMapper.selectById(id) == null) {
            throw new ServiceException(INTENT_NOT_EXISTS);
        }
        aiIntentMapper.deleteById(id); // 逻辑删除
    }

    @Override
    @Transactional
    public void replaceAutoIntents(Long kbId, List<AiIntentDO> intents) {
        // 先清旧 LLM_AUTO(逻辑删除), 再插新(MANUAL 不受影响)
        aiIntentMapper.deleteByKbIdAndSource(kbId, "LLM_AUTO");
        if (CollUtil.isEmpty(intents)) {
            return;
        }
        for (AiIntentDO intent : intents) {
            intent.setId(null);
            intent.setKbId(kbId);
            intent.setSource("LLM_AUTO"); // 强制来源, 防止外部误传
            intent.setStatus(0); // 默认启用
        }
        aiIntentMapper.insertBatch(intents);
    }

}
