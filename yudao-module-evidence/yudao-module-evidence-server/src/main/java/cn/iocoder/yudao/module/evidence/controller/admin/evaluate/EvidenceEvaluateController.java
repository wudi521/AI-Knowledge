package cn.iocoder.yudao.module.evidence.controller.admin.evaluate;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.evidence.controller.admin.evaluate.vo.EvidenceEvaluateReqVO;
import cn.iocoder.yudao.module.evidence.controller.admin.evaluate.vo.EvidenceEvaluateRespVO;
import cn.iocoder.yudao.module.evidence.service.EvidenceQueryEngineV3Facade;
import cn.iocoder.yudao.module.evidence.service.EvidenceQueryScopeResolver;
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

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 证据评估")
@RestController
@RequestMapping("/evidence")
@Validated
public class EvidenceEvaluateController {

    @Resource
    private EvidenceQueryEngineV3Facade queryEngineV3;
    @Resource
    private EvidenceQueryScopeResolver queryScopeResolver;

    @PostMapping("/evaluate")
    @Operation(summary = "Query Engine V3 单轮评估(Selection→bounded retrieval→EntitySet→Action→Evidence)")
    @PreAuthorize("@ss.hasPermission('evidence:evaluate')")
    public CommonResult<EvidenceEvaluateRespVO> evaluate(@Valid @RequestBody EvidenceEvaluateReqVO req) {
        LoginUser loginUser = SecurityFrameworkUtils.getLoginUser();
        Long userId = loginUser != null ? loginUser.getId() : null;
        Long tenantId = loginUser != null ? loginUser.getTenantId() : null;

        EvidenceQueryScopeResolver.Resolution scope = queryScopeResolver.resolve(req.getKbIds(), userId, null);
        if (!scope.allowed()) {
            return success(denied(req.getQuery(), scope.reasonCode(), scope.message()));
        }

        // 管理端明确为单轮；与 Chat 共用同一个 V3 Query Engine，只是不携带 history/context。
        return success(queryEngineV3.evaluate(req.getQuery(), scope.kbIds(), req.getTopK(),
                tenantId, userId, List.of(), req.getSkipSlotDetection(), null,
                scope.domainCode(), null, null));
    }

    private EvidenceEvaluateRespVO denied(String query, String reasonCode, String message) {
        EvidenceEvaluateRespVO resp = new EvidenceEvaluateRespVO();
        resp.setQuery(query);
        resp.setAnswerable(false);
        resp.setConfidence(0D);
        resp.setConsultable(false);
        resp.setRefusalReason(message);
        resp.setRoute("ABSTAIN");
        resp.setIntent("SCOPE_REJECTED");
        resp.setReasonCode(reasonCode);
        resp.setEvidence(List.of());
        resp.setConflicts(List.of());
        resp.setClaimFail(false);
        resp.setElapsedMs(0);
        resp.setStages(List.of());
        return resp;
    }
}
