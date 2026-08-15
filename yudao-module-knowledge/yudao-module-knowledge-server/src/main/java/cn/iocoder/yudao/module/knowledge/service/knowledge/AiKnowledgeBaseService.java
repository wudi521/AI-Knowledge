package cn.iocoder.yudao.module.knowledge.service.knowledge;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.knowledge.controller.admin.knowledge.vo.AiKnowledgeBasePageReqVO;
import cn.iocoder.yudao.module.knowledge.controller.admin.knowledge.vo.AiKnowledgeBaseSaveReqVO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.AiKnowledgeBaseDO;
import jakarta.validation.Valid;

/**
 * 知识库 Service 接口
 */
public interface AiKnowledgeBaseService {

    /** 创建知识库 */
    Long createAiKnowledgeBase(@Valid AiKnowledgeBaseSaveReqVO createReqVO);

    /** 更新知识库 */
    void updateAiKnowledgeBase(@Valid AiKnowledgeBaseSaveReqVO updateReqVO);

    /** 删除知识库 */
    void deleteAiKnowledgeBase(Long id);

    /** 获得知识库 */
    AiKnowledgeBaseDO getAiKnowledgeBase(Long id);

    /** 获得知识库分页 */
    PageResult<AiKnowledgeBaseDO> getAiKnowledgeBasePage(AiKnowledgeBasePageReqVO pageReqVO);

}
