package cn.iocoder.yudao.module.knowledge.controller.admin.conflict;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.conflict.ConflictDO;
import cn.iocoder.yudao.module.knowledge.service.conflict.ConflictService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 版本冲突")
@RestController
@RequestMapping("/knowledge/conflict")
@Validated
public class ConflictController {

    @Resource
    private ConflictService conflictService;

    @GetMapping("/list")
    @Operation(summary = "冲突列表(按文档/状态)")
    public CommonResult<List<ConflictDO>> getConflictList(@RequestParam("docId") Long docId,
                                                          @RequestParam(value = "status", required = false) String status) {
        return success(conflictService.getConflictList(docId, status));
    }

    @PostMapping("/detect")
    @Operation(summary = "触发冲突检测(发布前自动调用, 也可手动)")
    public CommonResult<Integer> detect(@RequestParam("versionId") Long versionId) {
        return success(conflictService.detectConflicts(versionId));
    }

    @PostMapping("/resolve")
    @Operation(summary = "裁决冲突(RESOLVED_NEW=以新版为准 / RESOLVED_OLD=以旧版为准)")
    public CommonResult<Boolean> resolve(@RequestParam("id") Long id,
                                         @RequestParam("resolveType") String resolveType,
                                         @RequestParam(value = "comment", required = false) String comment) {
        conflictService.resolve(id, resolveType, comment);
        return success(true);
    }

}
