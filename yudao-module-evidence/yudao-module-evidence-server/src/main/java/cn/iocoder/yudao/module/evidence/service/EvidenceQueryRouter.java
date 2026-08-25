package cn.iocoder.yudao.module.evidence.service;

import cn.iocoder.yudao.module.evidence.api.dto.ChatTurnDTO;
import cn.iocoder.yudao.module.evidence.api.dto.QueryPlanBudgetDTO;
import cn.iocoder.yudao.module.evidence.controller.admin.evaluate.vo.EvidenceEvaluateRespVO;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Evidence/Chat/Eval Runner 的统一顶层查询路由。
 *
 * <p>公共 Agentic Knowledge Runtime 是唯一在线查询主链。Router 不再注入或依赖 V2/V3 facade，
 * 因此旧 QueryIntent 体系即使仍保留作迁移对照，也不能参与生产路由或成为应用主链启动依赖。</p>
 */
@Service
public class EvidenceQueryRouter {
    private final AgenticEvidenceFacade agentFacade;

    public EvidenceQueryRouter(AgenticEvidenceFacade agentFacade) {
        this.agentFacade = agentFacade;
    }

    public EvidenceEvaluateRespVO evaluate(String query, List<Long> kbIds, Integer topK,
                                           Long tenantId, Long userId, List<ChatTurnDTO> history,
                                           Boolean skipSlotDetection, String traceId,
                                           String domainCode, String contextResolutionJson,
                                           QueryPlanBudgetDTO legacyBudget) {
        EvidenceEvaluateRespVO result = agentFacade.evaluateUnrecorded(query, kbIds, domainCode,
                tenantId, userId, history == null ? List.of() : history,
                contextResolutionJson, traceId);
        agentFacade.record(result);
        return result;
    }

    /** 在线执行固定为公共 Agent Runtime。 */
    public String mode() {
        return "AGENT";
    }
}
