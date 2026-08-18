package cn.iocoder.yudao.module.retrieval.controller.admin.search;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.retrieval.controller.admin.search.vo.RetrievalReqVO;
import cn.iocoder.yudao.module.retrieval.controller.admin.search.vo.RetrievalRespVO;
import cn.iocoder.yudao.module.retrieval.service.search.SearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 检索平台")
@RestController
@RequestMapping("/retrieval")
@Validated
public class RetrievalController {

    @Resource
    private SearchService searchService;

    @PostMapping("/search")
    @Operation(summary = "检索(混合检索 + 重排 + 权限/状态过滤)")
    @PreAuthorize("@ss.hasPermission('retrieval:search')")
    public CommonResult<RetrievalRespVO> search(@Valid @RequestBody RetrievalReqVO req) {
        return success(searchService.search(req));
    }

}
