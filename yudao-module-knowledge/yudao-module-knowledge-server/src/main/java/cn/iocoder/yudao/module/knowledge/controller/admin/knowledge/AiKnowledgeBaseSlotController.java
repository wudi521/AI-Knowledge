package cn.iocoder.yudao.module.knowledge.controller.admin.knowledge;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.knowledge.controller.admin.knowledge.vo.AiKnowledgeBaseSlotPageReqVO;
import cn.iocoder.yudao.module.knowledge.controller.admin.knowledge.vo.AiKnowledgeBaseSlotRespVO;
import cn.iocoder.yudao.module.knowledge.controller.admin.knowledge.vo.AiKnowledgeBaseSlotSaveReqVO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.AiKnowledgeBaseSlotDO;
import cn.iocoder.yudao.module.knowledge.service.knowledge.AiKnowledgeBaseSlotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 知识库槽位定义")
@RestController
@RequestMapping("/knowledge/kb-slot")
@Validated
public class AiKnowledgeBaseSlotController {

    @Resource
    private AiKnowledgeBaseSlotService aiKnowledgeBaseSlotService;

    @PostMapping("/create")
    @Operation(summary = "创建知识库槽位定义")
    @PreAuthorize("@ss.hasPermission('knowledge:kb-slot:create')")
    public CommonResult<Long> createAiKnowledgeBaseSlot(@Valid @RequestBody AiKnowledgeBaseSlotSaveReqVO createReqVO) {
        return success(aiKnowledgeBaseSlotService.createAiKnowledgeBaseSlot(createReqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新知识库槽位定义")
    @PreAuthorize("@ss.hasPermission('knowledge:kb-slot:update')")
    public CommonResult<Boolean> updateAiKnowledgeBaseSlot(@Valid @RequestBody AiKnowledgeBaseSlotSaveReqVO updateReqVO) {
        aiKnowledgeBaseSlotService.updateAiKnowledgeBaseSlot(updateReqVO);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除知识库槽位定义")
    @PreAuthorize("@ss.hasPermission('knowledge:kb-slot:delete')")
    public CommonResult<Boolean> deleteAiKnowledgeBaseSlot(@RequestParam("id") Long id) {
        aiKnowledgeBaseSlotService.deleteAiKnowledgeBaseSlot(id);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得知识库槽位定义")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('knowledge:kb-slot:query')")
    public CommonResult<AiKnowledgeBaseSlotRespVO> getAiKnowledgeBaseSlot(@RequestParam("id") Long id) {
        AiKnowledgeBaseSlotDO slot = aiKnowledgeBaseSlotService.getAiKnowledgeBaseSlot(id);
        return success(BeanUtils.toBean(slot, AiKnowledgeBaseSlotRespVO.class));
    }

    @GetMapping("/page")
    @Operation(summary = "获得知识库槽位定义分页")
    @PreAuthorize("@ss.hasPermission('knowledge:kb-slot:query')")
    public CommonResult<PageResult<AiKnowledgeBaseSlotRespVO>> getAiKnowledgeBaseSlotPage(@Valid AiKnowledgeBaseSlotPageReqVO pageReqVO) {
        PageResult<AiKnowledgeBaseSlotDO> pageResult = aiKnowledgeBaseSlotService.getAiKnowledgeBaseSlotPage(pageReqVO);
        return success(BeanUtils.toBean(pageResult, AiKnowledgeBaseSlotRespVO.class));
    }

}
