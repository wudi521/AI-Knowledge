package cn.iocoder.yudao.module.knowledge.service.knowledge;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.knowledge.controller.admin.knowledge.vo.AiKnowledgeBaseSlotPageReqVO;
import cn.iocoder.yudao.module.knowledge.controller.admin.knowledge.vo.AiKnowledgeBaseSlotSaveReqVO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.AiKnowledgeBaseSlotDO;
import cn.iocoder.yudao.module.knowledge.dal.mysql.knowledge.AiKnowledgeBaseMapper;
import cn.iocoder.yudao.module.knowledge.dal.mysql.knowledge.AiKnowledgeBaseSlotMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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
        slot.setSource("MANUAL"); // 手动创建固定 MANUAL; LLM_AUTO 仅由总结器写入
        mapper.insert(slot);
        return slot.getId();
    }

    @Override
    public void updateAiKnowledgeBaseSlot(AiKnowledgeBaseSlotSaveReqVO updateReqVO) {
        AiKnowledgeBaseSlotDO db = mapper.selectById(updateReqVO.getId());
        if (db == null) {
            throw new ServiceException(KB_SLOT_NOT_EXISTS);
        }
        // 重复编码校验(kbId/slotCode 变化时)
        if (!Objects.equals(db.getKbId(), updateReqVO.getKbId())
                || !Objects.equals(db.getSlotCode(), updateReqVO.getSlotCode())) {
            Long count = mapper.selectCount(new LambdaQueryWrapperX<AiKnowledgeBaseSlotDO>()
                    .eq(AiKnowledgeBaseSlotDO::getKbId, updateReqVO.getKbId())
                    .eq(AiKnowledgeBaseSlotDO::getSlotCode, updateReqVO.getSlotCode())
                    .ne(AiKnowledgeBaseSlotDO::getId, updateReqVO.getId()));
            if (count != null && count > 0) {
                throw new ServiceException(KB_SLOT_CODE_EXISTS);
            }
        }
        AiKnowledgeBaseSlotDO update = BeanUtils.toBean(updateReqVO, AiKnowledgeBaseSlotDO.class);
        // 编辑保护: 用户改过自动生成的槽位 → 翻转 MANUAL, 后续自动生成不再覆盖
        if ("LLM_AUTO".equals(db.getSource())) {
            update.setSource("MANUAL");
        }
        mapper.updateById(update);
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

    @Override
    @Transactional
    public int replaceAutoSlots(Long kbId, List<AiKnowledgeBaseSlotDO> slots) {
        mapper.deleteAutoByKbId(kbId, TenantContextHolder.getTenantId());
        if (slots == null || slots.isEmpty()) {
            return 0;
        }
        // 跳过与既有槽位(MANUAL, 用户创建/编辑过)同编码的: uk(kb_id,slot_code,deleted) 冲突, MANUAL 保持权威
        Set<String> existingCodes = mapper.selectList(new LambdaQueryWrapperX<AiKnowledgeBaseSlotDO>()
                        .eq(AiKnowledgeBaseSlotDO::getKbId, kbId))
                .stream().map(AiKnowledgeBaseSlotDO::getSlotCode)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        List<AiKnowledgeBaseSlotDO> toInsert = new ArrayList<>();
        for (AiKnowledgeBaseSlotDO slot : slots) {
            if (slot.getSlotCode() != null && existingCodes.contains(slot.getSlotCode())) {
                continue;
            }
            slot.setId(null);
            slot.setKbId(kbId);
            slot.setSource("LLM_AUTO");
            slot.setStatus(0); // 默认启用
            toInsert.add(slot);
        }
        if (!toInsert.isEmpty()) {
            mapper.insertBatch(toInsert);
        }
        return toInsert.size();
    }

}
