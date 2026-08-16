package cn.iocoder.yudao.module.knowledge.controller.admin.version;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.knowledge.controller.admin.version.vo.AiDocVersionRespVO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.version.AiDocVersionDO;
import cn.iocoder.yudao.module.knowledge.service.version.AiDocVersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.framework.common.util.object.BeanUtils.toBean;

@Tag(name = "管理后台 - AI 文档版本")
@RestController
@RequestMapping("/knowledge/version")
@Validated
public class VersionController {

    @Resource
    private AiDocVersionService aiDocVersionService;

    @GetMapping("/list")
    @Operation(summary = "按文档查询版本列表(时间线)")
    @Parameter(name = "docId", description = "文档编号", required = true)
    public CommonResult<List<AiDocVersionRespVO>> getVersionList(@RequestParam("docId") Long docId) {
        return success(toBean(aiDocVersionService.getVersionList(docId), AiDocVersionRespVO.class));
    }

    @PostMapping("/publish")
    @Operation(summary = "发布版本(审核完成或自动发布后触发)")
    public CommonResult<Boolean> publish(@RequestParam("id") Long id) {
        aiDocVersionService.publish(id);
        return success(true);
    }

    @PostMapping("/reject")
    @Operation(summary = "整体驳回版本(回 DRAFT)")
    public CommonResult<Boolean> reject(@RequestParam("id") Long id,
                                        @RequestParam("comment") String comment) {
        aiDocVersionService.reject(id, comment);
        return success(true);
    }

}
