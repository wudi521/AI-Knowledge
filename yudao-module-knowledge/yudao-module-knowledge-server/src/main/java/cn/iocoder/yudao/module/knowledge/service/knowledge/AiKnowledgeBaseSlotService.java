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

    /**
     * 覆盖式写入 LLM_AUTO 槽位(自动生成用): 物理删除该知识库全部 LLM_AUTO, 再批量插入
     * 事务内执行; MANUAL 槽位不受影响
     *
     * @param kbId  知识库编号
     * @param slots 新槽位(kbId/source/status 由本方法强制填充)
     */
    void replaceAutoSlots(Long kbId, List<AiKnowledgeBaseSlotDO> slots);

}
