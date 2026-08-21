package cn.iocoder.yudao.module.rule.service.rule;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.rule.controller.admin.rule.vo.AiRuleKeyInfoRespVO;
import cn.iocoder.yudao.module.rule.controller.admin.rule.vo.AiRulePageReqVO;
import cn.iocoder.yudao.module.rule.controller.admin.rule.vo.AiRuleSaveReqVO;
import cn.iocoder.yudao.module.rule.controller.admin.rule.vo.AiRuleUpdateReqVO;
import cn.iocoder.yudao.module.rule.controller.admin.rule.vo.AiRuleValidateReqVO;
import cn.iocoder.yudao.module.rule.dal.dataobject.rule.AiRuleDO;
import jakarta.validation.Valid;

import java.util.List;

/**
 * AI 硬规则 Service 接口
 */
public interface AiRuleService {

    /** 创建规则(试编译 DRL → 新版本, 默认停用) */
    Long createRule(@Valid AiRuleSaveReqVO req);

    /** 更新规则(仅停用版本可编辑, 试编译) */
    void updateRule(@Valid AiRuleUpdateReqVO req);

    /** 全量启用(同 key 其他启用行自动停用) */
    void enableRule(Long id);

    /** 灰度启用(需该 key 已有全量启用版本; 同 key 其他灰度行自动回退) */
    void grayEnableRule(Long id, List<Long> tenantIds);

    /** 关闭灰度(回到停用) */
    void grayOffRule(Long id);

    /** 获得规则 */
    AiRuleDO getRule(Long id);

    /** 删除规则(逻辑删除) */
    void deleteRule(Long id);

    /** 分页查询 */
    PageResult<AiRuleDO> getPage(AiRulePageReqVO reqVO);

    /** 按业务键列出所有版本(版本倒序) */
    List<AiRuleDO> listByKey(String key);

    /** 业务键汇总(key 级别信息) */
    List<AiRuleKeyInfoRespVO> keyList();

    /** 试运行(用 facts 跑该规则行的 DRL, 返回命中结论; 编译失败报错) */
    List<RuleResult> validate(@Valid AiRuleValidateReqVO req);

}
