package cn.iocoder.yudao.module.rule.controller.admin.rule;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.rule.controller.admin.rule.vo.AiRuleEnableReqVO;
import cn.iocoder.yudao.module.rule.controller.admin.rule.vo.AiRuleGrayReqVO;
import cn.iocoder.yudao.module.rule.controller.admin.rule.vo.AiRuleKeyInfoRespVO;
import cn.iocoder.yudao.module.rule.controller.admin.rule.vo.AiRulePageReqVO;
import cn.iocoder.yudao.module.rule.controller.admin.rule.vo.AiRuleRespVO;
import cn.iocoder.yudao.module.rule.controller.admin.rule.vo.AiRuleSaveReqVO;
import cn.iocoder.yudao.module.rule.controller.admin.rule.vo.AiRuleUpdateReqVO;
import cn.iocoder.yudao.module.rule.controller.admin.rule.vo.AiRuleValidateReqVO;
import cn.iocoder.yudao.module.rule.dal.dataobject.rule.AiRuleDO;
import cn.iocoder.yudao.module.rule.service.rule.AiRuleService;
import cn.iocoder.yudao.module.rule.service.rule.RuleResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - AI 硬规则管理")
@RestController
@RequestMapping("/rule")
@Validated
public class AiRuleController {

    @Resource
    private AiRuleService aiRuleService;

    @PostMapping("/create")
    @Operation(summary = "创建规则(新版本, 默认停用; 保存时试编译 DRL)")
    @PreAuthorize("@ss.hasPermission('rule:rule:create')")
    public CommonResult<Long> createRule(@Valid @RequestBody AiRuleSaveReqVO createReqVO) {
        return success(aiRuleService.createRule(createReqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新规则(仅停用版本, 保存时试编译)")
    @PreAuthorize("@ss.hasPermission('rule:rule:update')")
    public CommonResult<Boolean> updateRule(@Valid @RequestBody AiRuleUpdateReqVO updateReqVO) {
        aiRuleService.updateRule(updateReqVO);
        return success(true);
    }

    @PostMapping("/enable")
    @Operation(summary = "全量启用(同 key 其他启用行自动停用)")
    @PreAuthorize("@ss.hasPermission('rule:rule:update')")
    public CommonResult<Boolean> enableRule(@Valid @RequestBody AiRuleEnableReqVO reqVO) {
        aiRuleService.enableRule(reqVO.getId());
        return success(true);
    }

    @PostMapping("/gray-enable")
    @Operation(summary = "灰度启用(需该 key 已有全量启用版本)")
    @PreAuthorize("@ss.hasPermission('rule:rule:update')")
    public CommonResult<Boolean> grayEnableRule(@Valid @RequestBody AiRuleGrayReqVO reqVO) {
        aiRuleService.grayEnableRule(reqVO.getId(), reqVO.getTenantIds());
        return success(true);
    }

    @PostMapping("/gray-off")
    @Operation(summary = "关闭灰度(回到停用)")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('rule:rule:update')")
    public CommonResult<Boolean> grayOffRule(@RequestParam("id") Long id) {
        aiRuleService.grayOffRule(id);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得规则")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('rule:rule:query')")
    public CommonResult<AiRuleRespVO> getRule(@RequestParam("id") Long id) {
        return success(convert(aiRuleService.getRule(id)));
    }

    @GetMapping("/page")
    @Operation(summary = "获得规则分页")
    @PreAuthorize("@ss.hasPermission('rule:rule:query')")
    public CommonResult<PageResult<AiRuleRespVO>> getRulePage(@Valid AiRulePageReqVO pageReqVO) {
        PageResult<AiRuleDO> pageResult = aiRuleService.getPage(pageReqVO);
        List<AiRuleRespVO> list = pageResult.getList().stream().map(this::convert).collect(Collectors.toList());
        return success(new PageResult<>(list, pageResult.getTotal()));
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除规则(删除启用行后该 key 无规则可用)")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('rule:rule:delete')")
    public CommonResult<Boolean> deleteRule(@RequestParam("id") Long id) {
        aiRuleService.deleteRule(id);
        return success(true);
    }

    @GetMapping("/key-list")
    @Operation(summary = "获得业务键汇总")
    @PreAuthorize("@ss.hasPermission('rule:rule:query')")
    public CommonResult<List<AiRuleKeyInfoRespVO>> keyList() {
        return success(aiRuleService.keyList());
    }

    @PostMapping("/validate")
    @Operation(summary = "试运行(给定 facts 看命中结论)")
    @PreAuthorize("@ss.hasPermission('rule:rule:query')")
    public CommonResult<List<RuleResult>> validateRule(@Valid @RequestBody AiRuleValidateReqVO reqVO) {
        return success(aiRuleService.validate(reqVO));
    }

    private AiRuleRespVO convert(AiRuleDO rule) {
        AiRuleRespVO resp = BeanUtils.toBean(rule, AiRuleRespVO.class);
        resp.setGrayTenantIds(parseTenantIds(rule.getGrayTenantIds()));
        return resp;
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
