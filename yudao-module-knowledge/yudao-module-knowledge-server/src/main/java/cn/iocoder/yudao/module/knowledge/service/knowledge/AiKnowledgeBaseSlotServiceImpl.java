package cn.iocoder.yudao.module.knowledge.service.knowledge;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.knowledge.controller.admin.knowledge.vo.AiKnowledgeBaseSlotPageReqVO;
import cn.iocoder.yudao.module.knowledge.controller.admin.knowledge.vo.AiKnowledgeBaseSlotSaveReqVO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.AiKnowledgeBaseSlotDO;
import cn.iocoder.yudao.module.knowledge.dal.mysql.knowledge.AiKnowledgeBaseMapper;
import cn.iocoder.yudao.module.knowledge.dal.mysql.knowledge.AiKnowledgeBaseSlotMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Objects;

import static cn.iocoder.yudao.module.knowledge.enums.ErrorCodeConstants.INTENT_KB_NOT_EXISTS;
import static cn.iocoder.yudao.module.knowledge.enums.ErrorCodeConstants.KB_SLOT_CODE_EXISTS;
import static cn.iocoder.yudao.module.knowledge.enums.ErrorCodeConstants.KB_SLOT_NOT_EXISTS;

/**
 * 知识库槽位定义 Service 实现
 */
@Service
@Validated
public class AiKnowledgeBaseSlotServiceImpl implements AiKnowledgeBaseSlotService {

    @Resource
    private AiKnowledgeBaseSlotMapper mapper;

    @Resource
    private AiKnowledgeBaseMapper aiKnowledgeBaseMapper;

    @Override
    public Long createAiKnowledgeBaseSlot(AiKnowledgeBaseSlotSaveReqVO createReqVO) {
        // 校验知识库存在
        if (aiKnowledgeBaseMapper.selectById(createReqVO.getKbId()) == null) {
            throw new ServiceException(INTENT_KB_NOT_EXISTS);
        }
        // 校验 (kbId, slotCode) 唯一
        if (mapper.selectCount(new LambdaQueryWrapperX<AiKnowledgeBaseSlotDO>()
                .eq(AiKnowledgeBaseSlotDO::getKbId, createReqVO.getKbId())
                .eq(AiKnowledgeBaseSlotDO::getSlotCode, createReqVO.getSlotCode())) > 0) {
            throw new ServiceException(KB_SLOT_CODE_EXISTS);
        }
        AiKnowledgeBaseSlotDO slot = BeanUtils.toBean(createReqVO, AiKnowledgeBaseSlotDO.class);
        mapper.insert(slot);
        return slot.getId();
    }

    @Override
    public void updateAiKnowledgeBaseSlot(AiKnowledgeBaseSlotSaveReqVO updateReqVO) {
        // 校验槽位存在
        AiKnowledgeBaseSlotDO slot = mapper.selectById(updateReqVO.getId());
        if (slot == null) {
            throw new ServiceException(KB_SLOT_NOT_EXISTS);
        }
        // kbId 或 slotCode 变更时, 校验 (kbId, slotCode) 唯一(排除自身)
        if (!Objects.equals(slot.getKbId(), updateReqVO.getKbId())
                || !Objects.equals(slot.getSlotCode(), updateReqVO.getSlotCode())) {
            if (mapper.selectCount(new LambdaQueryWrapperX<AiKnowledgeBaseSlotDO>()
                    .eq(AiKnowledgeBaseSlotDO::getKbId, updateReqVO.getKbId())
                    .eq(AiKnowledgeBaseSlotDO::getSlotCode, updateReqVO.getSlotCode())
                    .ne(AiKnowledgeBaseSlotDO::getId, updateReqVO.getId())) > 0) {
                throw new ServiceException(KB_SLOT_CODE_EXISTS);
            }
        }
        mapper.updateById(BeanUtils.toBean(updateReqVO, AiKnowledgeBaseSlotDO.class));
    }

    @Override
    public void deleteAiKnowledgeBaseSlot(Long id) {
        mapper.deleteById(id);
    }

    @Override
    public AiKnowledgeBaseSlotDO getAiKnowledgeBaseSlot(Long id) {
        return mapper.selectById(id);
    }

    @Override
    public PageResult<AiKnowledgeBaseSlotDO> getAiKnowledgeBaseSlotPage(AiKnowledgeBaseSlotPageReqVO pageReqVO) {
        return mapper.selectPage(pageReqVO);
    }

    @Override
    public List<AiKnowledgeBaseSlotDO> getEnabledByKbIds(List<Long> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) {
            return List.of();
        }
        return mapper.selectList(new LambdaQueryWrapperX<AiKnowledgeBaseSlotDO>()
                .in(AiKnowledgeBaseSlotDO::getKbId, kbIds)
                .eq(AiKnowledgeBaseSlotDO::getStatus, CommonStatusEnum.ENABLE.getStatus())
                .orderByAsc(AiKnowledgeBaseSlotDO::getKbId)
                .orderByAsc(AiKnowledgeBaseSlotDO::getSort));
    }

}
