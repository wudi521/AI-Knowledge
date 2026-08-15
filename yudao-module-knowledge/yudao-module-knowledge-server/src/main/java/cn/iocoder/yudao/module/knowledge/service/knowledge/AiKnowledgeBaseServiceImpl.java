package cn.iocoder.yudao.module.knowledge.service.knowledge;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.knowledge.controller.admin.knowledge.vo.AiKnowledgeBasePageReqVO;
import cn.iocoder.yudao.module.knowledge.controller.admin.knowledge.vo.AiKnowledgeBaseSaveReqVO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.AiKnowledgeBaseDO;
import cn.iocoder.yudao.module.knowledge.dal.mysql.knowledge.AiKnowledgeBaseMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

/**
 * 知识库 Service 实现
 */
@Service
@Validated
public class AiKnowledgeBaseServiceImpl implements AiKnowledgeBaseService {

    @Resource
    private AiKnowledgeBaseMapper aiKnowledgeBaseMapper;

    @Override
    public Long createAiKnowledgeBase(AiKnowledgeBaseSaveReqVO createReqVO) {
        AiKnowledgeBaseDO knowledgeBase = BeanUtils.toBean(createReqVO, AiKnowledgeBaseDO.class);
        aiKnowledgeBaseMapper.insert(knowledgeBase);
        return knowledgeBase.getId();
    }

    @Override
    public void updateAiKnowledgeBase(AiKnowledgeBaseSaveReqVO updateReqVO) {
        AiKnowledgeBaseDO db = aiKnowledgeBaseMapper.selectById(updateReqVO.getId());
        if (db == null) {
            throw new IllegalArgumentException("知识库不存在");
        }
        AiKnowledgeBaseDO update = BeanUtils.toBean(updateReqVO, AiKnowledgeBaseDO.class);
        aiKnowledgeBaseMapper.updateById(update);
    }

    @Override
    public void deleteAiKnowledgeBase(Long id) {
        aiKnowledgeBaseMapper.deleteById(id);
    }

    @Override
    public AiKnowledgeBaseDO getAiKnowledgeBase(Long id) {
        return aiKnowledgeBaseMapper.selectById(id);
    }

    @Override
    public PageResult<AiKnowledgeBaseDO> getAiKnowledgeBasePage(AiKnowledgeBasePageReqVO pageReqVO) {
        return aiKnowledgeBaseMapper.selectPage(pageReqVO);
    }

}
