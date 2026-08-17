package cn.iocoder.yudao.module.knowledge.service.knowledge;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.knowledge.controller.admin.knowledge.vo.AiKnowledgeBasePageReqVO;
import cn.iocoder.yudao.module.knowledge.controller.admin.knowledge.vo.AiKnowledgeBaseSaveReqVO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.AiKnowledgeBaseDO;
import cn.iocoder.yudao.module.knowledge.dal.mysql.knowledge.AiKnowledgeBaseMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 知识库 Service 实现
 */
@Service
@Validated
public class AiKnowledgeBaseServiceImpl implements AiKnowledgeBaseService {

    @Resource
    private AiKnowledgeBaseMapper aiKnowledgeBaseMapper;

    @Resource
    private KnowledgePermissionHelper knowledgePermissionHelper;

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
        PageResult<AiKnowledgeBaseDO> pageResult = aiKnowledgeBaseMapper.selectPage(pageReqVO);
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        if (userId == null || knowledgePermissionHelper.isSuperAdmin(userId)) {
            return pageResult; // 内部调用/超管直通
        }
        // 收集本页所有可见角色 code 去重
        Set<String> candidateCodes = pageResult.getList().stream()
                .map(AiKnowledgeBaseDO::getVisibleRoles)
                .filter(StrUtil::isNotBlank)
                .flatMap(s -> StrUtil.split(s, ',').stream())
                .map(String::trim).collect(Collectors.toSet());
        Set<String> myRoles = knowledgePermissionHelper.resolveUserRoles(userId, candidateCodes);
        List<AiKnowledgeBaseDO> filtered = pageResult.getList().stream()
                .filter(kb -> knowledgePermissionHelper.visibleToUser(kb, myRoles))
                .toList();
        return new PageResult<>(filtered, (long) filtered.size());
    }

}
