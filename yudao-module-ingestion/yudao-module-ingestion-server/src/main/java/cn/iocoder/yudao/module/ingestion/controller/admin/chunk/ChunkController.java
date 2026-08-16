package cn.iocoder.yudao.module.ingestion.controller.admin.chunk;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.ingestion.controller.admin.chunk.vo.ChunkPageReqVO;
import cn.iocoder.yudao.module.ingestion.controller.admin.chunk.vo.ChunkRespVO;
import cn.iocoder.yudao.module.ingestion.controller.admin.chunk.vo.ChunkUpdateReqVO;
import cn.iocoder.yudao.module.ingestion.controller.admin.chunk.vo.ChunkUpdateStatusReqVO;
import cn.iocoder.yudao.module.ingestion.dal.dataobject.ChunkDO;
import cn.iocoder.yudao.module.ingestion.dal.mysql.ChunkMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.module.ingestion.enums.ErrorCodeConstants.CHUNK_NOT_EXISTS;

@Tag(name = "管理后台 - AI 知识片段")
@RestController
@RequestMapping("/ingestion/chunk")
@Validated
public class ChunkController {

    @Resource
    private ChunkMapper chunkMapper;

    @GetMapping("/page")
    @Operation(summary = "获得 AI 知识片段分页")
    @PreAuthorize("@ss.hasPermission('ai:knowledge:query')")
    public CommonResult<PageResult<ChunkRespVO>> getChunkPage(@Valid ChunkPageReqVO pageReqVO) {
        PageResult<ChunkDO> pageResult = chunkMapper.selectPage(pageReqVO);
        // ChunkDO.versionId 即文档编号(documentId), 手动映射
        List<ChunkRespVO> list = new ArrayList<>(pageResult.getList().size());
        for (ChunkDO chunk : pageResult.getList()) {
            ChunkRespVO respVO = BeanUtils.toBean(chunk, ChunkRespVO.class);
            respVO.setDocumentId(chunk.getVersionId());
            list.add(respVO);
        }
        return success(new PageResult<>(list, pageResult.getTotal()));
    }

    @PutMapping("/update")
    @Operation(summary = "编辑 AI 知识片段内容")
    @PreAuthorize("@ss.hasPermission('ai:knowledge:update')")
    public CommonResult<Boolean> updateChunk(@Valid @RequestBody ChunkUpdateReqVO updateReqVO) {
        // 校验片段存在
        validateChunkExists(updateReqVO.getId());
        // 仅更新内容
        ChunkDO update = new ChunkDO();
        update.setId(updateReqVO.getId());
        update.setContent(updateReqVO.getContent());
        chunkMapper.updateById(update);
        return success(true);
    }

    @PutMapping("/update-status")
    @Operation(summary = "启用/禁用 AI 知识片段")
    @PreAuthorize("@ss.hasPermission('ai:knowledge:update')")
    public CommonResult<Boolean> updateChunkStatus(@Valid @RequestBody ChunkUpdateStatusReqVO updateStatusReqVO) {
        // 校验片段存在
        validateChunkExists(updateStatusReqVO.getId());
        // 仅更新状态(PUBLISHED=启用 / DISABLED=禁用)
        ChunkDO update = new ChunkDO();
        update.setId(updateStatusReqVO.getId());
        update.setStatus(updateStatusReqVO.getStatus());
        chunkMapper.updateById(update);
        return success(true);
    }

    private void validateChunkExists(Long id) {
        if (chunkMapper.selectById(id) == null) {
            throw exception(CHUNK_NOT_EXISTS);
        }
    }

}
