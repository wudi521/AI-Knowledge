package cn.iocoder.yudao.module.evidence.controller.admin.evaluate;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.evidence.controller.admin.evaluate.vo.EvidenceEvaluateReqVO;
import cn.iocoder.yudao.module.evidence.controller.admin.evaluate.vo.EvidenceEvaluateRespVO;
import cn.iocoder.yudao.module.evidence.service.EvidenceService;
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

@Tag(name = "管理后台 - 证据评估")
@RestController
@RequestMapping("/evidence")
@Validated
public class EvidenceEvaluateController {

    @Resource
    private EvidenceService evidenceService;

    @PostMapping("/evaluate")
    @Operation(summary = "证据评估(检索→去重→冲突→充分性→生成+Claim验证→落库)")
    @PreAuthorize("@ss.hasPermission('evidence:evaluate')")
    public CommonResult<EvidenceEvaluateRespVO> evaluate(@Valid @RequestBody EvidenceEvaluateReqVO req) {
        return success(evidenceService.evaluate(req.getQuery(), req.getKbIds(), req.getTopK(),
                req.getSkipSlotDetection()));
    }

}
