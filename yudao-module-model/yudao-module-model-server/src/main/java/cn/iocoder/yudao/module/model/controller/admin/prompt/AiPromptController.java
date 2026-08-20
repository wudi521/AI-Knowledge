package cn.iocoder.yudao.module.model.controller.admin.prompt;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.model.controller.admin.prompt.vo.AiPromptEnableReqVO;
import cn.iocoder.yudao.module.model.controller.admin.prompt.vo.AiPromptGrayReqVO;
import cn.iocoder.yudao.module.model.controller.admin.prompt.vo.AiPromptKeyInfoRespVO;
import cn.iocoder.yudao.module.model.controller.admin.prompt.vo.AiPromptPageReqVO;
import cn.iocoder.yudao.module.model.controller.admin.prompt.vo.AiPromptRespVO;
import cn.iocoder.yudao.module.model.controller.admin.prompt.vo.AiPromptSaveReqVO;
import cn.iocoder.yudao.module.model.controller.admin.prompt.vo.AiPromptUpdateReqVO;
import cn.iocoder.yudao.module.model.dal.dataobject.prompt.AiPromptDO;
import cn.iocoder.yudao.module.model.service.prompt.AiPromptService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - AI Prompt 管理")
@RestController
@RequestMapping("/model/prompt")
@Validated
public class AiPromptController {

    @Resource
    private AiPromptService aiPromptService;

    @PostMapping("/create")
    @Operation(summary = "创建提示词(新版本, 默认停用)")
    @PreAuthorize("@ss.hasPermission('model:prompt:create')")
    public CommonResult<Long> createPrompt(@Valid @RequestBody AiPromptSaveReqVO createReqVO) {
        return success(aiPromptService.createPrompt(createReqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新提示词(仅停用版本)")
    @PreAuthorize("@ss.hasPermission('model:prompt:update')")
    public CommonResult<Boolean> updatePrompt(@Valid @RequestBody AiPromptUpdateReqVO updateReqVO) {
        aiPromptService.updatePrompt(updateReqVO);
        return success(true);
    }

    @PostMapping("/enable")
    @Operation(summary = "全量启用(同 key 其他启用行自动停用)")
    @PreAuthorize("@ss.hasPermission('model:prompt:update')")
    public CommonResult<Boolean> enablePrompt(@Valid @RequestBody AiPromptEnableReqVO reqVO) {
        aiPromptService.enablePrompt(reqVO.getId());
        return success(true);
    }

    @PostMapping("/gray-enable")
    @Operation(summary = "灰度启用(需该 key 已有全量启用版本)")
    @PreAuthorize("@ss.hasPermission('model:prompt:update')")
    public CommonResult<Boolean> grayEnablePrompt(@Valid @RequestBody AiPromptGrayReqVO reqVO) {
        aiPromptService.grayEnablePrompt(reqVO.getId(), reqVO.getTenantIds());
        return success(true);
    }

    @PostMapping("/gray-off")
    @Operation(summary = "关闭灰度(回到停用)")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('model:prompt:update')")
    public CommonResult<Boolean> grayOffPrompt(@RequestParam("id") Long id) {
        aiPromptService.grayOffPrompt(id);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得提示词")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('model:prompt:query')")
    public CommonResult<AiPromptRespVO> getPrompt(@RequestParam("id") Long id) {
        return success(convert(aiPromptService.getPrompt(id)));
    }

    @GetMapping("/page")
    @Operation(summary = "获得提示词分页")
    @PreAuthorize("@ss.hasPermission('model:prompt:query')")
    public CommonResult<PageResult<AiPromptRespVO>> getPromptPage(@Valid AiPromptPageReqVO pageReqVO) {
        PageResult<AiPromptDO> pageResult = aiPromptService.getPage(pageReqVO);
        List<AiPromptRespVO> list = pageResult.getList().stream().map(this::convert).collect(Collectors.toList());
        return success(new PageResult<>(list, pageResult.getTotal()));
    }

    @GetMapping("/key-list")
    @Operation(summary = "获得业务键汇总")
    @PreAuthorize("@ss.hasPermission('model:prompt:query')")
    public CommonResult<List<AiPromptKeyInfoRespVO>> keyList() {
        return success(aiPromptService.keyList());
    }

    private AiPromptRespVO convert(AiPromptDO prompt) {
        AiPromptRespVO resp = BeanUtils.toBean(prompt, AiPromptRespVO.class);
        resp.setGrayTenantIds(parseTenantIds(prompt.getGrayTenantIds()));
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
