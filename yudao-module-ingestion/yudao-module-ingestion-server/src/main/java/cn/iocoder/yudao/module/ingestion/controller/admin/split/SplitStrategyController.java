package cn.iocoder.yudao.module.ingestion.controller.admin.split;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.ingestion.split.SplitterFactory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 切分策略")
@RestController
@RequestMapping("/ingestion/split-strategies")
@Validated
public class SplitStrategyController {

    @Resource
    private SplitterFactory splitterFactory;

    @GetMapping("/list")
    @Operation(summary = "获得切分策略列表(全部已注册策略, 含 auto 自动选择)")
    @PreAuthorize("@ss.hasPermission('ai:knowledge:query')")
    public CommonResult<List<SplitterFactory.StrategyInfo>> list() {
        return success(splitterFactory.listStrategies());
    }

}
