package cn.iocoder.yudao.module.eval.controller.admin.cases;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.eval.controller.admin.cases.vo.EvalCasePageReqVO;
import cn.iocoder.yudao.module.eval.controller.admin.cases.vo.EvalCaseRespVO;
import cn.iocoder.yudao.module.eval.controller.admin.cases.vo.EvalCaseSaveReqVO;
import cn.iocoder.yudao.module.eval.controller.admin.cases.vo.EvalCaseUpdateReqVO;
import cn.iocoder.yudao.module.eval.dal.dataobject.cases.EvalCaseDO;
import cn.iocoder.yudao.module.eval.service.cases.EvalCaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 评测用例")
@RestController
@RequestMapping("/eval/case")
@Validated
public class EvalCaseController {

    @Resource
    private EvalCaseService evalCaseService;

    @PostMapping("/create")
    @Operation(summary = "创建评测用例")
    @PreAuthorize("@ss.hasPermission('eval:case:create')")
    public CommonResult<Long> createCase(@Valid @RequestBody EvalCaseSaveReqVO createReqVO) {
        return success(evalCaseService.createCase(createReqVO));
    }

    @PostMapping("/update")
    @Operation(summary = "更新评测用例")
    @PreAuthorize("@ss.hasPermission('eval:case:update')")
    public CommonResult<Boolean> updateCase(@Valid @RequestBody EvalCaseUpdateReqVO updateReqVO) {
        evalCaseService.updateCase(updateReqVO);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除评测用例")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('eval:case:delete')")
    public CommonResult<Boolean> deleteCase(@RequestParam("id") Long id) {
        evalCaseService.deleteCase(id);
        return success(true);
    }

    @PostMapping("/generate")
    @Operation(summary = "从知识库已发布内容自动生成评测用例(入库后一键评测; 该库已有≥5个用例时跳过)")
    @Parameter(name = "kbId", description = "知识库编号", required = true)
    @PreAuthorize("@ss.hasPermission('eval:case:create')")
    public CommonResult<Integer> generateCases(@RequestParam("kbId") Long kbId) {
        return success(evalCaseService.generateCases(kbId));
    }

    @GetMapping("/page")
    @Operation(summary = "获得评测用例分页")
    @PreAuthorize("@ss.hasPermission('eval:case:query')")
    public CommonResult<PageResult<EvalCaseRespVO>> getCasePage(@Valid EvalCasePageReqVO pageReqVO) {
        PageResult<EvalCaseDO> pageResult = evalCaseService.getCasePage(pageReqVO);
        return success(new PageResult<>(pageResult.getList().stream()
                .map(this::toRespVO).toList(), pageResult.getTotal()));
    }

    @GetMapping("/get")
    @Operation(summary = "获得评测用例")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('eval:case:query')")
    public CommonResult<EvalCaseRespVO> getCase(@RequestParam("id") Long id) {
        return success(toRespVO(evalCaseService.getCase(id)));
    }

    /**
     * DO → VO: goldChunks 原始 JSON 由 DO 侧解析到 VO.goldChunks(BeanUtils 按属性名拷贝,
     * DO.goldChunks(String) 与 VO.goldChunks(List) 类型不匹配会被跳过, 需显式解析)
     */
    private EvalCaseRespVO toRespVO(EvalCaseDO evalCase) {
        EvalCaseRespVO respVO = BeanUtils.toBean(evalCase, EvalCaseRespVO.class);
        respVO.setGoldChunks(parseGoldChunks(evalCase.getGoldChunks()));
        return respVO;
    }

    /**
     * 解析标准证据 JSON 数组; 空/脏数据返回 null, 不阻断查询
     */
    private List<Long> parseGoldChunks(String goldChunksJson) {
        if (StrUtil.isBlank(goldChunksJson)) {
            return null;
        }
        try {
            return JSONUtil.toList(goldChunksJson, Long.class);
        } catch (Exception e) {
            return null;
        }
    }

}
