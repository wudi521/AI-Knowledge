package cn.iocoder.yudao.module.workflow.controller.admin.health;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 业务流程健康检查")
@RestController
@RequestMapping("/workflow/health")
public class WorkflowHealthController {

    @GetMapping("/ping")
    @Operation(summary = "业务流程服务存活")
    public CommonResult<String> ping() {
        return success("pong");
    }

}
