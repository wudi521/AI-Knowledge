package cn.iocoder.yudao.module.rule.service.rule.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.rule.controller.admin.rule.vo.AiRuleKeyInfoRespVO;
import cn.iocoder.yudao.module.rule.controller.admin.rule.vo.AiRulePageReqVO;
import cn.iocoder.yudao.module.rule.controller.admin.rule.vo.AiRuleSaveReqVO;
import cn.iocoder.yudao.module.rule.controller.admin.rule.vo.AiRuleUpdateReqVO;
import cn.iocoder.yudao.module.rule.controller.admin.rule.vo.AiRuleValidateReqVO;
import cn.iocoder.yudao.module.rule.dal.dataobject.rule.AiRuleDO;
import cn.iocoder.yudao.module.rule.dal.mysql.rule.AiRuleMapper;
import cn.iocoder.yudao.module.rule.service.rule.AiRuleService;
import cn.iocoder.yudao.module.rule.service.rule.RuleEngine;
import cn.iocoder.yudao.module.rule.service.rule.RuleResult;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mzt.logapi.context.LogRecordContext;
import com.mzt.logapi.starter.annotation.LogRecord;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.rule.enums.ErrorCodeConstants.AI_RULE_COMPILE_FAILED;
import static cn.iocoder.yudao.module.rule.enums.ErrorCodeConstants.AI_RULE_GRAY_NEED_ENABLED;
import static cn.iocoder.yudao.module.rule.enums.ErrorCodeConstants.AI_RULE_NOT_EDITABLE;
import static cn.iocoder.yudao.module.rule.enums.ErrorCodeConstants.AI_RULE_NOT_EXISTS;
import static cn.iocoder.yudao.module.rule.enums.RuleLogRecordConstants.*;

/**
 * AI 硬规则 Service 实现
 * <p>
 * 保存/更新/试运行时试编译 DRL(编译失败报错不入库/不生效, 不影响已启用版本)
 */
@Slf4j
@Service
@Validated
public class AiRuleServiceImpl implements AiRuleService {

    @Resource
    private AiRuleMapper aiRuleMapper;
    @Resource
    private RuleEngine ruleEngine;

    @Override
    @LogRecord(type = RULE_TYPE, subType = RULE_CREATE_SUB_TYPE, bizNo = "{{#ruleId}}",
            success = RULE_CREATE_SUCCESS)
    public Long createRule(AiRuleSaveReqVO req) {
        // 试编译: DRL 非法直接报错, 不入库
        compileOrThrow(req.getRuleKey(), req.getDrlContent());
        AiRuleDO rule = BeanUtils.toBean(req, AiRuleDO.class);
        // 版本号: 同 key 最大版本 + 1, 无历史则为 1
        List<AiRuleDO> rows = aiRuleMapper.selectByKeyOrdered(req.getRuleKey());
        int nextVersion = rows.isEmpty() ? 1 : rows.get(0).getVersion() + 1;
        rule.setVersion(nextVersion);
        rule.setStatus(0); // 新版本默认停用
        aiRuleMapper.insert(rule);
        LogRecordContext.putVariable("ruleId", rule.getId());
        return rule.getId();
    }

    @Override
    @LogRecord(type = RULE_TYPE, subType = RULE_UPDATE_SUB_TYPE, bizNo = "{{#req.id}}",
            success = RULE_UPDATE_SUCCESS)
    public void updateRule(AiRuleUpdateReqVO req) {
        // 校验存在 + 仅停用版本可编辑
        AiRuleDO rule = validateRuleExists(req.getId());
        if (!Integer.valueOf(0).equals(rule.getStatus())) {
            throw exception(AI_RULE_NOT_EDITABLE);
        }
        // 试编译
        compileOrThrow(rule.getRuleKey(), req.getDrlContent());
        // 更新 name/description/drlContent
        AiRuleDO updateObj = BeanUtils.toBean(req, AiRuleDO.class);
        aiRuleMapper.updateById(updateObj);
    }

    @Override
    @LogRecord(type = RULE_TYPE, subType = RULE_ENABLE_SUB_TYPE, bizNo = "{{#id}}",
            success = RULE_ENABLE_SUCCESS)
    public void enableRule(Long id) {
        AiRuleDO rule = validateRuleExists(id);
        LogRecordContext.putVariable("rule", rule);
        // 当前行启用
        AiRuleDO updateObj = new AiRuleDO();
        updateObj.setId(id);
        updateObj.setStatus(1);
        aiRuleMapper.updateById(updateObj);
        // 同 key 其他启用行停用(保证全量启用唯一)
        aiRuleMapper.update(null, new LambdaUpdateWrapper<AiRuleDO>()
                .eq(AiRuleDO::getRuleKey, rule.getRuleKey())
                .ne(AiRuleDO::getId, id)
                .eq(AiRuleDO::getStatus, 1)
                .set(AiRuleDO::getStatus, 0));
    }

    @Override
    @LogRecord(type = RULE_TYPE, subType = RULE_GRAY_ENABLE_SUB_TYPE, bizNo = "{{#id}}",
            success = RULE_GRAY_ENABLE_SUCCESS)
    public void grayEnableRule(Long id, List<Long> tenantIds) {
        AiRuleDO rule = validateRuleExists(id);
        LogRecordContext.putVariable("rule", rule);
        // 该 key 必须另有全量启用版本(目标行自身不算), 否则灰度后无全量版本可用
        List<AiRuleDO> enabled = aiRuleMapper.selectByKeyAndStatusIn(rule.getRuleKey(), List.of(1));
        boolean hasOtherEnabled = enabled.stream().anyMatch(e -> !e.getId().equals(id));
        if (!hasOtherEnabled) {
            throw exception(AI_RULE_GRAY_NEED_ENABLED);
        }
        // 当前行灰度启用
        AiRuleDO updateObj = new AiRuleDO();
        updateObj.setId(id);
        updateObj.setStatus(2);
        updateObj.setGrayTenantIds(JSONUtil.toJsonStr(tenantIds));
        aiRuleMapper.updateById(updateObj);
        // 同 key 其他灰度行回退(保证灰度唯一)
        aiRuleMapper.update(null, new LambdaUpdateWrapper<AiRuleDO>()
                .eq(AiRuleDO::getRuleKey, rule.getRuleKey())
                .ne(AiRuleDO::getId, id)
                .eq(AiRuleDO::getStatus, 2)
                .set(AiRuleDO::getStatus, 0)
                .set(AiRuleDO::getGrayTenantIds, null));
    }

    @Override
    @LogRecord(type = RULE_TYPE, subType = RULE_GRAY_OFF_SUB_TYPE, bizNo = "{{#id}}",
            success = RULE_GRAY_OFF_SUCCESS)
    public void grayOffRule(Long id) {
        AiRuleDO rule = validateRuleExists(id);
        LogRecordContext.putVariable("rule", rule);
        // 灰度行回退为停用并清空灰度租户
        aiRuleMapper.update(null, new LambdaUpdateWrapper<AiRuleDO>()
                .eq(AiRuleDO::getId, id)
                .eq(AiRuleDO::getStatus, 2)
                .set(AiRuleDO::getStatus, 0)
                .set(AiRuleDO::getGrayTenantIds, null));
    }

    @Override
    public AiRuleDO getRule(Long id) {
        return aiRuleMapper.selectById(id);
    }

    @Override
    @LogRecord(type = RULE_TYPE, subType = RULE_DELETE_SUB_TYPE, bizNo = "{{#id}}",
            success = RULE_DELETE_SUCCESS)
    public void deleteRule(Long id) {
        AiRuleDO rule = validateRuleExists(id);
        LogRecordContext.putVariable("rule", rule);
        aiRuleMapper.deleteById(id); // 逻辑删除
    }

    @Override
    public PageResult<AiRuleDO> getPage(AiRulePageReqVO reqVO) {
        return aiRuleMapper.selectPage(reqVO);
    }

    @Override
    public List<AiRuleDO> listByKey(String key) {
        return aiRuleMapper.selectByKeyOrdered(key);
    }

    @Override
    public List<AiRuleKeyInfoRespVO> keyList() {
        List<AiRuleDO> all = aiRuleMapper.selectList();
        Map<String, List<AiRuleDO>> grouped = all.stream()
                .collect(Collectors.groupingBy(AiRuleDO::getRuleKey, LinkedHashMap::new, Collectors.toList()));
        List<AiRuleKeyInfoRespVO> result = new ArrayList<>();
        grouped.forEach((key, rows) -> {
            AiRuleKeyInfoRespVO vo = new AiRuleKeyInfoRespVO();
            vo.setRuleKey(key);
            // 名称取最新版本
            List<AiRuleDO> sorted = rows.stream()
                    .sorted(Comparator.comparing(AiRuleDO::getVersion, Comparator.nullsLast(Comparator.reverseOrder())))
                    .collect(Collectors.toList());
            vo.setName(sorted.get(0).getName());
            // 全量启用/灰度版本(各自唯一)
            AiRuleDO enabled = rows.stream()
                    .filter(r -> Integer.valueOf(1).equals(r.getStatus())).findFirst().orElse(null);
            AiRuleDO gray = rows.stream()
                    .filter(r -> Integer.valueOf(2).equals(r.getStatus())).findFirst().orElse(null);
            vo.setEnabledVersion(enabled != null ? enabled.getVersion() : null);
            vo.setGrayVersion(gray != null ? gray.getVersion() : null);
            vo.setGrayTenantIds(gray != null ? parseTenantIds(gray.getGrayTenantIds()) : null);
            vo.setVersionCount(rows.size());
            result.add(vo);
        });
        return result;
    }

    @Override
    public List<RuleResult> validate(AiRuleValidateReqVO req) {
        AiRuleDO rule = validateRuleExists(req.getId());
        return ruleEngine.validate(rule.getRuleKey(), rule.getDrlContent(), req.getFacts());
    }

    private AiRuleDO validateRuleExists(Long id) {
        AiRuleDO rule = aiRuleMapper.selectById(id);
        if (rule == null) {
            throw exception(AI_RULE_NOT_EXISTS);
        }
        return rule;
    }

    /**
     * 试编译 DRL: 失败抛业务异常(带 drools 错误明细), 由管理端展示
     */
    private void compileOrThrow(String ruleKey, String drl) {
        try {
            ruleEngine.validate(ruleKey, drl, null);
        } catch (IllegalArgumentException e) {
            throw exception(AI_RULE_COMPILE_FAILED, e.getMessage());
        }
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
