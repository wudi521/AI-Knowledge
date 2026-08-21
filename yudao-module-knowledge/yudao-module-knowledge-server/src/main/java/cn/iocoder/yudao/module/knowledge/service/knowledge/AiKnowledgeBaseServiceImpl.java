package cn.iocoder.yudao.module.knowledge.service.knowledge;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.knowledge.controller.admin.knowledge.vo.AiKnowledgeBasePageReqVO;
import cn.iocoder.yudao.module.knowledge.controller.admin.knowledge.vo.AiKnowledgeBaseSaveReqVO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.AiKnowledgeBaseDO;
import cn.iocoder.yudao.module.knowledge.dal.mysql.knowledge.AiKnowledgeBaseMapper;
import cn.iocoder.yudao.module.knowledge.service.slot.SlotSummarizer;
import com.mzt.logapi.context.LogRecordContext;
import com.mzt.logapi.starter.annotation.LogRecord;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.module.knowledge.enums.KnowledgeLogRecordConstants.*;

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

    @Resource
    private SlotSummarizer slotSummarizer;

    @Override
    @LogRecord(type = KB_TYPE, subType = KB_CREATE_SUB_TYPE, bizNo = "{{#kb.id}}",
            success = KB_CREATE_SUCCESS)
    public Long createAiKnowledgeBase(AiKnowledgeBaseSaveReqVO createReqVO) {
        AiKnowledgeBaseDO knowledgeBase = BeanUtils.toBean(createReqVO, AiKnowledgeBaseDO.class);
        aiKnowledgeBaseMapper.insert(knowledgeBase);
        LogRecordContext.putVariable("kb", knowledgeBase);
        // 新建即触发槽位自动总结(空库无内容 → 优雅跳过; 发布后会自动再触发)
        slotSummarizer.summarizeByKbAsync(knowledgeBase.getId());
        return knowledgeBase.getId();
    }

    @Override
    @LogRecord(type = KB_TYPE, subType = KB_UPDATE_SUB_TYPE, bizNo = "{{#updateReqVO.id}}",
            success = KB_UPDATE_SUCCESS)
    public void updateAiKnowledgeBase(AiKnowledgeBaseSaveReqVO updateReqVO) {
        AiKnowledgeBaseDO db = aiKnowledgeBaseMapper.selectById(updateReqVO.getId());
        if (db == null) {
            throw new IllegalArgumentException("知识库不存在");
        }
        AiKnowledgeBaseDO update = BeanUtils.toBean(updateReqVO, AiKnowledgeBaseDO.class);
        aiKnowledgeBaseMapper.updateById(update);
    }

    @Override
    @LogRecord(type = KB_TYPE, subType = KB_DELETE_SUB_TYPE, bizNo = "{{#id}}",
            success = KB_DELETE_SUCCESS)
    public void deleteAiKnowledgeBase(Long id) {
        // 先查对象再删, 供操作日志模板引用对象名({{#kb?.name}}); 对象不存在时原样执行删除(空操作), 模板安全导航渲染为空
        AiKnowledgeBaseDO kb = aiKnowledgeBaseMapper.selectById(id);
        LogRecordContext.putVariable("kb", kb);
        aiKnowledgeBaseMapper.deleteById(id);
    }

    @Override
    public AiKnowledgeBaseDO getAiKnowledgeBase(Long id) {
        return aiKnowledgeBaseMapper.selectById(id);
    }

    @Override
    public PageResult<AiKnowledgeBaseDO> getAiKnowledgeBasePage(AiKnowledgeBasePageReqVO pageReqVO) {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        if (userId == null || knowledgePermissionHelper.isSuperAdmin(userId)) {
            return aiKnowledgeBaseMapper.selectPage(pageReqVO); // 内部调用/超管直通
        }
        // 非超管: 全量查 -> 角色/有效期过滤 -> 内存分页(保证 total 为真实可见数, 避免深分页空页)
        List<AiKnowledgeBaseDO> visible = knowledgePermissionHelper.filterVisibleKbs(userId, aiKnowledgeBaseMapper.selectList());
        int from = (pageReqVO.getPageNo() - 1) * pageReqVO.getPageSize();
        List<AiKnowledgeBaseDO> pageList = visible.stream()
                .skip(Math.max(from, 0))
                .limit(pageReqVO.getPageSize())
                .toList();
        return new PageResult<>(pageList, (long) visible.size());
    }

}
