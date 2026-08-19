package cn.iocoder.yudao.module.eval.controller.admin.task;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.eval.controller.admin.task.vo.EvalTaskPageReqVO;
import cn.iocoder.yudao.module.eval.controller.admin.task.vo.EvalTaskRespVO;
import cn.iocoder.yudao.module.eval.controller.admin.task.vo.EvalTaskResultRespVO;
import cn.iocoder.yudao.module.eval.controller.admin.task.vo.EvalTaskRunReqVO;
import cn.iocoder.yudao.module.eval.dal.dataobject.task.EvalTaskDO;
import cn.iocoder.yudao.module.eval.service.report.EvalReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 评测任务")
@RestController
@RequestMapping("/eval/task")
@Validated
public class EvalTaskController {

    @Resource
    private EvalReportService evalReportService;

    @PostMapping("/run")
    @Operation(summary = "发起评测任务(异步执行, 立即返回任务编号)")
    @PreAuthorize("@ss.hasPermission('eval:task:run')")
    public CommonResult<Long> runTask(@Valid @RequestBody EvalTaskRunReqVO runReqVO) {
        return success(evalReportService.createAndRun(runReqVO));
    }

    @GetMapping("/page")
    @Operation(summary = "获得评测任务分页")
    @PreAuthorize("@ss.hasPermission('eval:task:query')")
    public CommonResult<PageResult<EvalTaskRespVO>> getTaskPage(@Valid EvalTaskPageReqVO pageReqVO) {
        PageResult<EvalTaskDO> pageResult = evalReportService.getTaskPage(pageReqVO);
        List<EvalTaskRespVO> list = pageResult.getList().stream()
                .map(evalReportService::toRespVO).toList();
        return success(new PageResult<>(list, pageResult.getTotal()));
    }

    @GetMapping("/get")
    @Operation(summary = "获得评测任务")
    @Parameter(name = "id", description = "任务编号", required = true)
    @PreAuthorize("@ss.hasPermission('eval:task:query')")
    public CommonResult<EvalTaskRespVO> getTask(@RequestParam("id") Long id) {
        return success(evalReportService.getTask(id));
    }

    @GetMapping("/results")
    @Operation(summary = "获得评测任务逐题结果")
    @Parameter(name = "taskId", description = "任务编号", required = true)
    @PreAuthorize("@ss.hasPermission('eval:task:query')")
    public CommonResult<List<EvalTaskResultRespVO>> getTaskResults(@RequestParam("taskId") Long taskId) {
        return success(evalReportService.getResults(taskId));
    }

}
