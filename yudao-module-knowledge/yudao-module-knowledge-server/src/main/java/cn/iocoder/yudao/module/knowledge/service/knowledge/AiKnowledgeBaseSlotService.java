package cn.iocoder.yudao.module.knowledge.service.knowledge;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.knowledge.controller.admin.knowledge.vo.AiKnowledgeBaseSlotPageReqVO;
import cn.iocoder.yudao.module.knowledge.controller.admin.knowledge.vo.AiKnowledgeBaseSlotSaveReqVO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.AiKnowledgeBaseSlotDO;
import jakarta.validation.Valid;

import java.util.List;

/**
 * 知识库槽位定义 Service 接口
 */
public interface AiKnowledgeBaseSlotService {

    Long createAiKnowledgeBaseSlot(@Valid AiKnowledgeBaseSlotSaveReqVO createReqVO);

    void updateAiKnowledgeBaseSlot(@Valid AiKnowledgeBaseSlotSaveReqVO updateReqVO);

    void deleteAiKnowledgeBaseSlot(Long id);

    AiKnowledgeBaseSlotDO getAiKnowledgeBaseSlot(Long id);

    PageResult<AiKnowledgeBaseSlotDO> getAiKnowledgeBaseSlotPage(AiKnowledgeBaseSlotPageReqVO pageReqVO);

    /** 批量查询启用槽位定义(按 kb_id+sort 升序; RPC 用) */
    List<AiKnowledgeBaseSlotDO> getEnabledByKbIds(List<Long> kbIds);

}
