package cn.iocoder.yudao.module.knowledge.controller.admin.knowledge;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.knowledge.controller.admin.knowledge.vo.AiKnowledgeBasePageReqVO;
import cn.iocoder.yudao.module.knowledge.controller.admin.knowledge.vo.AiKnowledgeBaseRespVO;
import cn.iocoder.yudao.module.knowledge.controller.admin.knowledge.vo.AiKnowledgeBaseSaveReqVO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.AiKnowledgeBaseDO;
import cn.iocoder.yudao.module.knowledge.service.knowledge.AiKnowledgeBaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 知识库")
@RestController
@RequestMapping("/knowledge/knowledge-base")
@Validated
public class AiKnowledgeBaseController {

    @Resource
    private AiKnowledgeBaseService aiKnowledgeBaseService;

    @PostMapping("/create")
    @Operation(summary = "创建知识库")
    @PreAuthorize("@ss.hasPermission('knowledge:knowledge-base:create')")
    public CommonResult<Long> createAiKnowledgeBase(@Valid @RequestBody AiKnowledgeBaseSaveReqVO createReqVO) {
        return success(aiKnowledgeBaseService.createAiKnowledgeBase(createReqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新知识库")
    @PreAuthorize("@ss.hasPermission('knowledge:knowledge-base:update')")
    public CommonResult<Boolean> updateAiKnowledgeBase(@Valid @RequestBody AiKnowledgeBaseSaveReqVO updateReqVO) {
        aiKnowledgeBaseService.updateAiKnowledgeBase(updateReqVO);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除知识库")
    @PreAuthorize("@ss.hasPermission('knowledge:knowledge-base:delete')")
    public CommonResult<Boolean> deleteAiKnowledgeBase(@RequestParam("id") Long id) {
        aiKnowledgeBaseService.deleteAiKnowledgeBase(id);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得知识库")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('knowledge:knowledge-base:query')")
    public CommonResult<AiKnowledgeBaseRespVO> getAiKnowledgeBase(@RequestParam("id") Long id) {
        AiKnowledgeBaseDO knowledgeBase = aiKnowledgeBaseService.getAiKnowledgeBase(id);
        return success(BeanUtils.toBean(knowledgeBase, AiKnowledgeBaseRespVO.class));
    }

    @GetMapping("/page")
    @Operation(summary = "获得知识库分页")
    @PreAuthorize("@ss.hasPermission('knowledge:knowledge-base:query')")
    public CommonResult<PageResult<AiKnowledgeBaseRespVO>> getAiKnowledgeBasePage(@Valid AiKnowledgeBasePageReqVO pageReqVO) {
        PageResult<AiKnowledgeBaseDO> pageResult = aiKnowledgeBaseService.getAiKnowledgeBasePage(pageReqVO);
        return success(BeanUtils.toBean(pageResult, AiKnowledgeBaseRespVO.class));
    }

}
