package cn.iocoder.yudao.module.model.service.prompt.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.model.controller.admin.prompt.vo.AiPromptKeyInfoRespVO;
import cn.iocoder.yudao.module.model.controller.admin.prompt.vo.AiPromptPageReqVO;
import cn.iocoder.yudao.module.model.controller.admin.prompt.vo.AiPromptSaveReqVO;
import cn.iocoder.yudao.module.model.controller.admin.prompt.vo.AiPromptUpdateReqVO;
import cn.iocoder.yudao.module.model.dal.dataobject.prompt.AiPromptDO;
import cn.iocoder.yudao.module.model.dal.mysql.prompt.AiPromptMapper;
import cn.iocoder.yudao.module.model.service.prompt.AiPromptService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mzt.logapi.context.LogRecordContext;
import com.mzt.logapi.starter.annotation.LogRecord;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.model.enums.ErrorCodeConstants.AI_PROMPT_GRAY_NEED_ENABLED;
import static cn.iocoder.yudao.module.model.enums.ErrorCodeConstants.AI_PROMPT_NOT_EDITABLE;
import static cn.iocoder.yudao.module.model.enums.ErrorCodeConstants.AI_PROMPT_NOT_EXISTS;
import static cn.iocoder.yudao.module.model.enums.ModelLogRecordConstants.*;

/**
 * AI Prompt Service 实现
 */
@Service
@Validated
public class AiPromptServiceImpl implements AiPromptService {

    @Resource
    private AiPromptMapper aiPromptMapper;

    @Override
    @LogRecord(type = PROMPT_TYPE, subType = PROMPT_CREATE_SUB_TYPE, bizNo = "{{#promptId}}",
            success = PROMPT_CREATE_SUCCESS)
    public Long createPrompt(AiPromptSaveReqVO req) {
        AiPromptDO prompt = BeanUtils.toBean(req, AiPromptDO.class);
        // 版本号: 同 key 最大版本 + 1, 无历史则为 1
        List<AiPromptDO> rows = aiPromptMapper.selectByKeyOrdered(req.getPromptKey());
        int nextVersion = rows.isEmpty() ? 1 : rows.get(0).getVersion() + 1;
        prompt.setVersion(nextVersion);
        prompt.setStatus(0); // 新版本默认停用
        aiPromptMapper.insert(prompt);
        LogRecordContext.putVariable("promptId", prompt.getId());
        LogRecordContext.putVariable("newVersion", nextVersion);
        return prompt.getId();
    }

    @Override
    @LogRecord(type = PROMPT_TYPE, subType = PROMPT_UPDATE_SUB_TYPE, bizNo = "{{#req.id}}",
            success = PROMPT_UPDATE_SUCCESS)
    public void updatePrompt(AiPromptUpdateReqVO req) {
        // 校验存在 + 仅停用版本可编辑
        AiPromptDO prompt = validatePromptExists(req.getId());
        if (!Integer.valueOf(0).equals(prompt.getStatus())) {
            throw exception(AI_PROMPT_NOT_EDITABLE);
        }
        // 更新 name/description/content
        AiPromptDO updateObj = BeanUtils.toBean(req, AiPromptDO.class);
        aiPromptMapper.updateById(updateObj);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(type = PROMPT_TYPE, subType = PROMPT_ENABLE_SUB_TYPE, bizNo = "{{#id}}",
            success = PROMPT_ENABLE_SUCCESS)
    public void enablePrompt(Long id) {
        AiPromptDO prompt = validatePromptExists(id);
        LogRecordContext.putVariable("prompt", prompt);
        // 当前行启用
        AiPromptDO updateObj = new AiPromptDO();
        updateObj.setId(id);
        updateObj.setStatus(1);
        aiPromptMapper.updateById(updateObj);
        // 同 key 其他启用行停用(保证全量启用唯一)
        aiPromptMapper.update(null, new LambdaUpdateWrapper<AiPromptDO>()
                .eq(AiPromptDO::getPromptKey, prompt.getPromptKey())
                .ne(AiPromptDO::getId, id)
                .eq(AiPromptDO::getStatus, 1)
                .set(AiPromptDO::getStatus, 0));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(type = PROMPT_TYPE, subType = PROMPT_GRAY_ENABLE_SUB_TYPE, bizNo = "{{#id}}",
            success = PROMPT_GRAY_ENABLE_SUCCESS)
    public void grayEnablePrompt(Long id, List<Long> tenantIds) {
        AiPromptDO prompt = validatePromptExists(id);
        LogRecordContext.putVariable("prompt", prompt);
        // 该 key 必须另有全量启用版本(目标行自身不算), 否则灰度后无全量版本可用
        List<AiPromptDO> enabled = aiPromptMapper.selectByKeyAndStatusIn(prompt.getPromptKey(), List.of(1));
        boolean hasOtherEnabled = enabled.stream().anyMatch(e -> !e.getId().equals(id));
        if (!hasOtherEnabled) {
            throw exception(AI_PROMPT_GRAY_NEED_ENABLED);
        }
        // 当前行灰度启用
        AiPromptDO updateObj = new AiPromptDO();
        updateObj.setId(id);
        updateObj.setStatus(2);
        updateObj.setGrayTenantIds(JSONUtil.toJsonStr(tenantIds));
        aiPromptMapper.updateById(updateObj);
        // 同 key 其他灰度行回退(保证灰度唯一)
        aiPromptMapper.update(null, new LambdaUpdateWrapper<AiPromptDO>()
                .eq(AiPromptDO::getPromptKey, prompt.getPromptKey())
                .ne(AiPromptDO::getId, id)
                .eq(AiPromptDO::getStatus, 2)
                .set(AiPromptDO::getStatus, 0)
                .set(AiPromptDO::getGrayTenantIds, null));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(type = PROMPT_TYPE, subType = PROMPT_GRAY_OFF_SUB_TYPE, bizNo = "{{#id}}",
            success = PROMPT_GRAY_OFF_SUCCESS)
    public void grayOffPrompt(Long id) {
        AiPromptDO prompt = validatePromptExists(id);
        LogRecordContext.putVariable("prompt", prompt);
        // 灰度行回退为停用并清空灰度租户
        aiPromptMapper.update(null, new LambdaUpdateWrapper<AiPromptDO>()
                .eq(AiPromptDO::getId, id)
                .eq(AiPromptDO::getStatus, 2)
                .set(AiPromptDO::getStatus, 0)
                .set(AiPromptDO::getGrayTenantIds, null));
    }

    @Override
    public AiPromptDO getPrompt(Long id) {
        return aiPromptMapper.selectById(id);
    }

    @Override
    @LogRecord(type = PROMPT_TYPE, subType = PROMPT_DELETE_SUB_TYPE, bizNo = "{{#id}}",
            success = PROMPT_DELETE_SUCCESS)
    public void deletePrompt(Long id) {
        AiPromptDO prompt = validatePromptExists(id);
        LogRecordContext.putVariable("prompt", prompt);
        aiPromptMapper.deleteById(id); // 逻辑删除
    }

    @Override
    public PageResult<AiPromptDO> getPage(AiPromptPageReqVO reqVO) {
        return aiPromptMapper.selectPage(reqVO);
    }

    @Override
    public List<AiPromptDO> listByKey(String key) {
        return aiPromptMapper.selectByKeyOrdered(key);
    }

    @Override
    public List<AiPromptKeyInfoRespVO> keyList() {
        List<AiPromptDO> all = aiPromptMapper.selectList();
        Map<String, List<AiPromptDO>> grouped = all.stream()
                .collect(Collectors.groupingBy(AiPromptDO::getPromptKey, LinkedHashMap::new, Collectors.toList()));
        List<AiPromptKeyInfoRespVO> result = new ArrayList<>();
        grouped.forEach((key, rows) -> {
            AiPromptKeyInfoRespVO vo = new AiPromptKeyInfoRespVO();
            vo.setPromptKey(key);
            // 名称取最新版本
            List<AiPromptDO> sorted = rows.stream()
                    .sorted(Comparator.comparing(AiPromptDO::getVersion, Comparator.nullsLast(Comparator.reverseOrder())))
                    .collect(Collectors.toList());
            vo.setName(sorted.get(0).getName());
            // 全量启用/灰度版本(各自唯一)
            AiPromptDO enabled = rows.stream()
                    .filter(r -> Integer.valueOf(1).equals(r.getStatus())).findFirst().orElse(null);
            AiPromptDO gray = rows.stream()
                    .filter(r -> Integer.valueOf(2).equals(r.getStatus())).findFirst().orElse(null);
            vo.setEnabledVersion(enabled != null ? enabled.getVersion() : null);
            vo.setGrayVersion(gray != null ? gray.getVersion() : null);
            vo.setGrayTenantIds(gray != null ? parseTenantIds(gray.getGrayTenantIds()) : null);
            vo.setVersionCount(rows.size());
            result.add(vo);
        });
        return result;
    }

    private AiPromptDO validatePromptExists(Long id) {
        AiPromptDO prompt = aiPromptMapper.selectById(id);
        if (prompt == null) {
            throw exception(AI_PROMPT_NOT_EXISTS);
        }
        return prompt;
    }

    private List<Long> parseTenantIds(String json) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        try {
            return JSONUtil.toList(json, Long.class);
        } catch (Exception e) {
            return List.of();
        }
    }

}
