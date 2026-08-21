package cn.iocoder.yudao.module.model.controller.admin.cost;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.model.service.cost.CostService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - AI 成本管理")
@RestController
@RequestMapping("/model/cost")
@Validated
public class AiCostController {

    @Resource
    private CostService costService;

    @GetMapping("/summary")
    @Operation(summary = "成本汇总(近 N 天调用量/token/成功率/平均耗时/估算成本)")
    @PreAuthorize("@ss.hasPermission('model:cost:query')")
    public CommonResult<CostService.CostSummaryResp> summary(
            @RequestParam(value = "recentDays", required = false) Integer recentDays) {
        return success(costService.summary(recentDays));
    }

    @GetMapping("/trend")
    @Operation(summary = "成本趋势(近 N 天按天调用量/token/成本)")
    @PreAuthorize("@ss.hasPermission('model:cost:query')")
    public CommonResult<List<CostService.CostTrendItem>> trend(
            @RequestParam(value = "days", required = false) Integer days) {
        return success(costService.trend(days));
    }

    @GetMapping("/by-tenant")
    @Operation(summary = "租户分摊(当前租户视角下各租户调用分布; 单租户部署为单行)")
    @PreAuthorize("@ss.hasPermission('model:cost:query')")
    public CommonResult<List<CostService.CostGroupItem>> byTenant(
            @RequestParam(value = "recentDays", required = false) Integer recentDays) {
        return success(costService.byTenant(recentDays));
    }

    @GetMapping("/by-scenario")
    @Operation(summary = "场景分布(查询分析/槽位检测/回答生成等)")
    @PreAuthorize("@ss.hasPermission('model:cost:query')")
    public CommonResult<List<CostService.CostGroupItem>> byScenario(
            @RequestParam(value = "recentDays", required = false) Integer recentDays) {
        return success(costService.byScenario(recentDays));
    }

    @GetMapping("/by-model")
    @Operation(summary = "模型分布(各模型调用量/token/估算成本)")
    @PreAuthorize("@ss.hasPermission('model:cost:query')")
    public CommonResult<List<CostService.CostGroupItem>> byModel(
            @RequestParam(value = "recentDays", required = false) Integer recentDays) {
        return success(costService.byModel(recentDays));
    }

    @GetMapping("/by-status")
    @Operation(summary = "状态分布(SUCCESS/FAILED/DEGRADED)")
    @PreAuthorize("@ss.hasPermission('model:cost:query')")
    public CommonResult<List<CostService.CostGroupItem>> byStatus(
            @RequestParam(value = "recentDays", required = false) Integer recentDays) {
        return success(costService.byStatus(recentDays));
    }

}
