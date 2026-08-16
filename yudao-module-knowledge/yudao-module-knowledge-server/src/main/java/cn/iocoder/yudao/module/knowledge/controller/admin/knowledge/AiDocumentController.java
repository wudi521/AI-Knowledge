package cn.iocoder.yudao.module.knowledge.controller.admin.knowledge;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.knowledge.controller.admin.knowledge.vo.AiDocumentPageReqVO;
import cn.iocoder.yudao.module.knowledge.controller.admin.knowledge.vo.AiDocumentRespVO;
import cn.iocoder.yudao.module.knowledge.controller.admin.knowledge.vo.AiDocumentSaveReqVO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.AiDocumentDO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.AiKnowledgeBaseDO;
import cn.iocoder.yudao.module.knowledge.dal.mysql.knowledge.AiKnowledgeBaseMapper;
import cn.iocoder.yudao.module.knowledge.service.knowledge.AiDocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - AI 文档")
@RestController
@RequestMapping("/knowledge/document")
@Validated
public class AiDocumentController {

    @Resource
    private AiDocumentService aiDocumentService;

    @Resource
    private AiKnowledgeBaseMapper aiKnowledgeBaseMapper;

    @PostMapping("/create")
    @Operation(summary = "创建文档(上传后登记)")
    @PreAuthorize("@ss.hasPermission('knowledge:document:create')")
    public CommonResult<Long> createAiDocument(@Valid @RequestBody AiDocumentSaveReqVO createReqVO) {
        return success(aiDocumentService.createAiDocument(createReqVO));
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除文档")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('knowledge:document:delete')")
    public CommonResult<Boolean> deleteAiDocument(@RequestParam("id") Long id) {
        aiDocumentService.deleteAiDocument(id);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得文档")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('knowledge:document:query')")
    public CommonResult<AiDocumentRespVO> getAiDocument(@RequestParam("id") Long id) {
        AiDocumentDO doc = aiDocumentService.getAiDocument(id);
        return success(BeanUtils.toBean(doc, AiDocumentRespVO.class));
    }

    @GetMapping("/page")
    @Operation(summary = "获得文档分页")
    @PreAuthorize("@ss.hasPermission('knowledge:document:query')")
    public CommonResult<PageResult<AiDocumentRespVO>> getAiDocumentPage(@Valid AiDocumentPageReqVO pageReqVO) {
        PageResult<AiDocumentDO> pageResult = aiDocumentService.getAiDocumentPage(pageReqVO);
        PageResult<AiDocumentRespVO> voPageResult = BeanUtils.toBean(pageResult, AiDocumentRespVO.class);
        // 联表填充知识库信息(名称/切分策略/Embedding 模型), 知识库可能被删故 null 安全
        for (AiDocumentRespVO respVO : voPageResult.getList()) {
            if (respVO.getKbId() == null) {
                continue;
            }
            AiKnowledgeBaseDO knowledgeBase = aiKnowledgeBaseMapper.selectById(respVO.getKbId());
            if (knowledgeBase != null) {
                respVO.setKbName(knowledgeBase.getName());
                respVO.setChunkStrategy(knowledgeBase.getChunkStrategy());
                respVO.setEmbedModel(knowledgeBase.getEmbedModel());
            }
        }
        return success(voPageResult);
    }

}
