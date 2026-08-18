package cn.iocoder.yudao.module.knowledge.controller.admin.intent;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.knowledge.controller.admin.intent.vo.IntentRespVO;
import cn.iocoder.yudao.module.knowledge.controller.admin.intent.vo.IntentSaveReqVO;
import cn.iocoder.yudao.module.knowledge.controller.admin.intent.vo.IntentUpdateReqVO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.intent.AiIntentDO;
import cn.iocoder.yudao.module.knowledge.service.intent.IntentService;
import cn.iocoder.yudao.module.knowledge.service.intent.IntentSummarizer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - AI 意图")
@RestController
@RequestMapping("/knowledge/intent")
@Validated
public class IntentController {

    @Resource
    private IntentService intentService;

    @Resource
    private IntentSummarizer intentSummarizer;

    @GetMapping("/list")
    @Operation(summary = "获得知识库下的意图列表(含停用)")
    @Parameter(name = "kbId", description = "知识库编号", required = true)
    @PreAuthorize("@ss.hasPermission('knowledge:intent:query')")
    public CommonResult<List<IntentRespVO>> getIntentList(@RequestParam("kbId") Long kbId) {
        List<AiIntentDO> list = intentService.listByKb(kbId);
        return success(BeanUtils.toBean(list, IntentRespVO.class));
    }

    @PostMapping("/create")
    @Operation(summary = "创建意图")
    @PreAuthorize("@ss.hasPermission('knowledge:intent:create')")
    public CommonResult<Long> createIntent(@Valid @RequestBody IntentSaveReqVO createReqVO) {
        return success(intentService.createIntent(createReqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新意图")
    @PreAuthorize("@ss.hasPermission('knowledge:intent:update')")
    public CommonResult<Boolean> updateIntent(@Valid @RequestBody IntentUpdateReqVO updateReqVO) {
        intentService.updateIntent(updateReqVO);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除意图")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('knowledge:intent:delete')")
    public CommonResult<Boolean> deleteIntent(@RequestParam("id") Long id) {
        intentService.deleteIntent(id);
        return success(true);
    }

    @PostMapping("/summarize")
    @Operation(summary = "LLM 总结知识库意图(覆盖 LLM_AUTO, MANUAL 保留)")
    @Parameter(name = "kbId", description = "知识库编号", required = true)
    @PreAuthorize("@ss.hasPermission('knowledge:intent:update')")
    public CommonResult<Integer> summarize(@RequestParam("kbId") Long kbId) {
        return success(intentSummarizer.summarizeByKb(kbId));
    }

}
