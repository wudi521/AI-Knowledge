package cn.iocoder.yudao.module.evidence.controller.admin.evaluate;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.evidence.controller.admin.evaluate.vo.EvidenceEvaluateReqVO;
import cn.iocoder.yudao.module.evidence.controller.admin.evaluate.vo.EvidenceEvaluateRespVO;
import cn.iocoder.yudao.module.evidence.service.AgenticEvidenceFacade;
import cn.iocoder.yudao.module.evidence.service.EvidenceQueryEngineV3Facade;
import cn.iocoder.yudao.module.evidence.service.EvidenceQueryRouter;
import cn.iocoder.yudao.module.evidence.service.EvidenceQueryScopeResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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

    private final EvidenceQueryRouter queryRouter;
    private final EvidenceQueryEngineV3Facade queryEngineV3Facade;
    private final AgenticEvidenceFacade agenticEvidenceFacade;
    private final EvidenceQueryScopeResolver queryScopeResolver;

    public EvidenceEvaluateController(EvidenceQueryRouter queryRouter,
                                      EvidenceQueryEngineV3Facade queryEngineV3Facade,
                                      AgenticEvidenceFacade agenticEvidenceFacade,
                                      EvidenceQueryScopeResolver queryScopeResolver) {
        this.queryRouter = queryRouter;
        this.queryEngineV3Facade = queryEngineV3Facade;
        this.agenticEvidenceFacade = agenticEvidenceFacade;
        this.queryScopeResolver = queryScopeResolver;
    }

    @PostMapping("/evaluate")
    @Operation(summary = "统一查询评估入口(按 yudao.evidence.agent.mode 路由 V1.1/V3)")
    @PreAuthorize("@ss.hasPermission('evidence:evaluate')")
    public CommonResult<EvidenceEvaluateRespVO> evaluate(@Valid @RequestBody EvidenceEvaluateReqVO req) {
        ScopeContext ctx = resolve(req);
        if (!ctx.scope().allowed()) return success(denied(req.getQuery(), ctx.scope().reasonCode(), ctx.scope().message()));
        return success(queryRouter.evaluate(req.getQuery(), ctx.scope().kbIds(), req.getTopK(),
                ctx.tenantId(), ctx.userId(), List.of(), req.getSkipSlotDetection(), null,
                ctx.scope().domainCode(), null, null));
    }

    @PostMapping("/evaluate-agent-v1")
    @Operation(summary = "强制 Agentic RAG V1.1 单轮评估(A/B 回归入口)")
    @PreAuthorize("@ss.hasPermission('evidence:evaluate')")
    public CommonResult<EvidenceEvaluateRespVO> evaluateAgentV1(@Valid @RequestBody EvidenceEvaluateReqVO req) {
        ScopeContext ctx = resolve(req);
        if (!ctx.scope().allowed()) return success(denied(req.getQuery(), ctx.scope().reasonCode(), ctx.scope().message()));
        return success(agenticEvidenceFacade.evaluate(req.getQuery(), ctx.scope().kbIds(), ctx.scope().domainCode(),
                ctx.tenantId(), ctx.userId(), List.of()));
    }

    @PostMapping("/evaluate-v3")
    @Operation(summary = "强制 Query Engine V3 单轮评估(A/B 基线入口)")
    @PreAuthorize("@ss.hasPermission('evidence:evaluate')")
    public CommonResult<EvidenceEvaluateRespVO> evaluateV3(@Valid @RequestBody EvidenceEvaluateReqVO req) {
        ScopeContext ctx = resolve(req);
        if (!ctx.scope().allowed()) return success(denied(req.getQuery(), ctx.scope().reasonCode(), ctx.scope().message()));
        return success(queryEngineV3Facade.evaluate(req.getQuery(), ctx.scope().kbIds(), req.getTopK(),
                ctx.tenantId(), ctx.userId(), List.of(), req.getSkipSlotDetection(), null,
                ctx.scope().domainCode(), null, null));
    }

    private ScopeContext resolve(EvidenceEvaluateReqVO req) {
        LoginUser loginUser = SecurityFrameworkUtils.getLoginUser();
        Long userId = loginUser != null ? loginUser.getId() : null;
        Long tenantId = loginUser != null ? loginUser.getTenantId() : null;
        EvidenceQueryScopeResolver.Resolution scope = queryScopeResolver.resolve(req.getKbIds(), userId, null);
        return new ScopeContext(userId, tenantId, scope);
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

    private record ScopeContext(Long userId, Long tenantId, EvidenceQueryScopeResolver.Resolution scope) { }
}
