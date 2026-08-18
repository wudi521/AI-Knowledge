package cn.iocoder.yudao.module.ingestion.controller.admin.chunk;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.ingestion.controller.admin.chunk.vo.ChunkPageReqVO;
import cn.iocoder.yudao.module.ingestion.controller.admin.chunk.vo.ChunkRespVO;
import cn.iocoder.yudao.module.ingestion.controller.admin.chunk.vo.ChunkUpdateReqVO;
import cn.iocoder.yudao.module.ingestion.controller.admin.chunk.vo.ChunkUpdateStatusReqVO;
import cn.iocoder.yudao.module.ingestion.dal.dataobject.ChunkDO;
import cn.iocoder.yudao.module.ingestion.service.chunk.ChunkService;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeDocumentRespDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeVersionRespDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - AI 知识片段")
@RestController
@RequestMapping("/ingestion/chunk")
@Validated
public class ChunkController {

    @Resource
    private ChunkService chunkService;

    @Resource
    private KnowledgeApi knowledgeApi;

    @GetMapping("/page")
    @Operation(summary = "获得 AI 知识片段分页")
    @PreAuthorize("@ss.hasPermission('ai:knowledge:query')")
    public CommonResult<PageResult<ChunkRespVO>> getChunkPage(@Valid ChunkPageReqVO pageReqVO) {
        PageResult<ChunkDO> pageResult = chunkService.getChunkPage(pageReqVO);
        List<ChunkDO> chunks = pageResult.getList();
        // 批量解析版本信息(versionId -> docId/versionNo), 避免逐行 Feign; knowledge 异常时降级为空(不阻塞页面)
        Map<Long, KnowledgeVersionRespDTO> versionMap;
        try {
            versionMap = cn.hutool.core.collection.CollUtil.isEmpty(chunks) ? Map.of()
                    : knowledgeApi.getVersionMap(chunks.stream().map(ChunkDO::getVersionId).distinct().toList()).getCheckedData();
        } catch (Exception e) {
            versionMap = Map.of();
        }
        // 文档信息按 docId 缓存(文档可能被删, 为空时跳过)
        Map<Long, KnowledgeDocumentRespDTO> docCache = new HashMap<>();
        List<ChunkRespVO> list = new ArrayList<>(chunks.size());
        for (ChunkDO chunk : chunks) {
            KnowledgeVersionRespDTO version = versionMap.get(chunk.getVersionId());
            Long docId = version == null ? null : version.getDocId();
            ChunkRespVO respVO = BeanUtils.toBean(chunk, ChunkRespVO.class);
            respVO.setDocumentId(docId);
            respVO.setVersionNo(version == null ? null : version.getVersionNo());
            if (docId != null) {
                KnowledgeDocumentRespDTO doc = docCache.computeIfAbsent(docId, id -> {
                    CommonResult<KnowledgeDocumentRespDTO> r = knowledgeApi.getDocument(id);
                    return r.isError() || r.getData() == null ? null : r.getData();
                });
                if (doc != null) {
                    respVO.setDocumentName(doc.getName());
                    respVO.setStoragePath(doc.getStoragePath());
                }
            }
            list.add(respVO);
        }
        return success(new PageResult<>(list, pageResult.getTotal()));
    }

    @GetMapping("/get")
    @Operation(summary = "获得 AI 知识片段详情(含内容)")
    @PreAuthorize("@ss.hasPermission('ai:knowledge:query')")
    public CommonResult<ChunkRespVO> getChunk(@RequestParam("id") Long id) {
        ChunkDO chunk = chunkService.getChunk(id);
        return success(chunk == null ? null : BeanUtils.toBean(chunk, ChunkRespVO.class));
    }

    @PutMapping("/update")
    @Operation(summary = "编辑 AI 知识片段内容")
    @PreAuthorize("@ss.hasPermission('ai:knowledge:update')")
    public CommonResult<Boolean> updateChunk(@Valid @RequestBody ChunkUpdateReqVO updateReqVO) {
        chunkService.updateChunk(updateReqVO.getId(), updateReqVO.getContent());
        return success(true);
    }

    @PutMapping("/update-status")
    @Operation(summary = "启用/禁用 AI 知识片段")
    @PreAuthorize("@ss.hasPermission('ai:knowledge:update')")
    public CommonResult<Boolean> updateChunkStatus(@Valid @RequestBody ChunkUpdateStatusReqVO updateStatusReqVO) {
        chunkService.updateChunkStatus(updateStatusReqVO.getId(), updateStatusReqVO.getStatus());
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除片段(三处联动)")
    @Parameter(name = "id", description = "片段编号", required = true)
    @PreAuthorize("@ss.hasPermission('ai:knowledge:update')")
    public CommonResult<Boolean> deleteChunk(@RequestParam("id") Long id) {
        chunkService.deleteChunk(id);
        return success(true);
    }

    @DeleteMapping("/delete-batch")
    @Operation(summary = "批量删除片段(三处联动)")
    @Parameter(name = "ids", description = "片段编号列表, 逗号分隔", required = true)
    @PreAuthorize("@ss.hasPermission('ai:knowledge:update')")
    public CommonResult<Boolean> deleteChunkBatch(@RequestParam("ids") List<Long> ids) {
        chunkService.deleteChunks(ids);
        return success(true);
    }

}
