package cn.iocoder.yudao.module.knowledge.controller.admin.acl;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.knowledge.controller.admin.acl.vo.AiResourceAclSaveReqVO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.acl.AiResourceAclDO;
import cn.iocoder.yudao.module.knowledge.dal.mysql.acl.AiResourceAclMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 管理后台 - 资源 ACL(企业级分层权限, 批次 D1)
 */
@Tag(name = "管理后台 - 资源 ACL")
@RestController
@RequestMapping("/knowledge/acl")
@Validated
public class AiResourceAclController {

    @Resource
    private AiResourceAclMapper aclMapper;

    @PostMapping("/create")
    @Operation(summary = "创建 ACL 规则")
    @PreAuthorize("@ss.hasPermission('knowledge:acl:create')")
    public CommonResult<Long> create(@Valid @RequestBody AiResourceAclSaveReqVO req) {
        AiResourceAclDO acl = BeanUtils.toBean(req, AiResourceAclDO.class);
        aclMapper.insert(acl);
        return success(acl.getId());
    }

    @PutMapping("/update")
    @Operation(summary = "更新 ACL 规则")
    @PreAuthorize("@ss.hasPermission('knowledge:acl:update')")
    public CommonResult<Boolean> update(@Valid @RequestBody AiResourceAclSaveReqVO req) {
        aclMapper.updateById(BeanUtils.toBean(req, AiResourceAclDO.class));
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除 ACL 规则")
    @PreAuthorize("@ss.hasPermission('knowledge:acl:delete')")
    public CommonResult<Boolean> delete(@RequestParam("id") Long id) {
        aclMapper.deleteById(id);
        return success(true);
    }

    @GetMapping("/page")
    @Operation(summary = "ACL 规则分页")
    @PreAuthorize("@ss.hasPermission('knowledge:acl:query')")
    public CommonResult<PageResult<AiResourceAclDO>> page(@RequestParam(value = "resourceType", required = false) String resourceType,
                                                          @RequestParam(value = "resourceId", required = false) Long resourceId,
                                                          @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
                                                          @RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize) {
        PageParam pageParam = new PageParam();
        pageParam.setPageNo(pageNo);
        pageParam.setPageSize(pageSize);
        return success(aclMapper.selectPage(pageParam,
                new LambdaQueryWrapperX<AiResourceAclDO>()
                        .eqIfPresent(AiResourceAclDO::getResourceType, resourceType)
                        .eqIfPresent(AiResourceAclDO::getResourceId, resourceId)
                        .orderByAsc(AiResourceAclDO::getResourceType).orderByAsc(AiResourceAclDO::getResourceId)));
    }

}
