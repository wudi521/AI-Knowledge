package cn.iocoder.yudao.module.model.controller.admin.model;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.model.controller.admin.model.vo.AiModelConfigPageReqVO;
import cn.iocoder.yudao.module.model.controller.admin.model.vo.AiModelConfigRespVO;
import cn.iocoder.yudao.module.model.controller.admin.model.vo.AiModelConfigSaveReqVO;
import cn.iocoder.yudao.module.model.dal.dataobject.model.AiModelConfigDO;
import cn.iocoder.yudao.module.model.service.model.AiModelConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 模型配置")
@RestController
@RequestMapping("/model/model-config")
@Validated
public class AiModelConfigController {

    @Resource
    private AiModelConfigService aiModelConfigService;

    @PostMapping("/create")
    @Operation(summary = "创建模型配置")
    @PreAuthorize("@ss.hasPermission('model:model-config:create')")
    public CommonResult<Long> createAiModelConfig(@Valid @RequestBody AiModelConfigSaveReqVO createReqVO) {
        return success(aiModelConfigService.createAiModelConfig(createReqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新模型配置")
    @PreAuthorize("@ss.hasPermission('model:model-config:update')")
    public CommonResult<Boolean> updateAiModelConfig(@Valid @RequestBody AiModelConfigSaveReqVO updateReqVO) {
        aiModelConfigService.updateAiModelConfig(updateReqVO);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除模型配置")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('model:model-config:delete')")
    public CommonResult<Boolean> deleteAiModelConfig(@RequestParam("id") Long id) {
        aiModelConfigService.deleteAiModelConfig(id);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得模型配置")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('model:model-config:query')")
    public CommonResult<AiModelConfigRespVO> getAiModelConfig(@RequestParam("id") Long id) {
        AiModelConfigDO config = aiModelConfigService.getAiModelConfig(id);
        return success(BeanUtils.toBean(config, AiModelConfigRespVO.class));
    }

    @GetMapping("/page")
    @Operation(summary = "获得模型配置分页")
    @PreAuthorize("@ss.hasPermission('model:model-config:query')")
    public CommonResult<PageResult<AiModelConfigRespVO>> getAiModelConfigPage(@Valid AiModelConfigPageReqVO pageReqVO) {
        PageResult<AiModelConfigDO> pageResult = aiModelConfigService.getAiModelConfigPage(pageReqVO);
        return success(BeanUtils.toBean(pageResult, AiModelConfigRespVO.class));
    }

    @PostMapping("/encrypt-legacy-api-keys")
    @Operation(summary = "遗留明文 API Key 加密迁移(幂等; 需配置 YUDAO_SECRET_MASTER_KEY)")
    @PreAuthorize("@ss.hasPermission('model:model-config:update')")
    public CommonResult<Integer> encryptLegacyApiKeys() {
        return success(aiModelConfigService.encryptLegacyApiKeys());
    }

    @GetMapping("/list")
    @Operation(summary = "获得指定类型的已启用模型列表(供下拉)")
    @Parameter(name = "type", description = "类型: chat/embedding/rerank", required = true)
    @PreAuthorize("@ss.hasPermission('model:model-config:query')")
    public CommonResult<List<AiModelConfigRespVO>> getEnableModelList(@RequestParam("type") String type) {
        List<AiModelConfigDO> list = aiModelConfigService.getEnableModelListByType(type);
        return success(BeanUtils.toBean(list, AiModelConfigRespVO.class));
    }

}
