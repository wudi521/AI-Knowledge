package cn.iocoder.yudao.module.evidence.service;

import cn.iocoder.yudao.module.evidence.api.dto.ChatTurnDTO;
import cn.iocoder.yudao.module.evidence.api.dto.QueryPlanBudgetDTO;
import cn.iocoder.yudao.module.evidence.controller.admin.evaluate.vo.EvidenceEvaluateRespVO;
import cn.iocoder.yudao.module.evidence.framework.evidence.EvidenceProperties;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Evidence/Chat/Eval Runner 的统一顶层查询路由。
 *
 * <p>公共 Agentic Knowledge Runtime 已成为唯一在线主链路。V3 依赖保留在构造参数中仅用于
 * 迁移期二进制/测试兼容，不再用于 fallback；否则旧 QueryIntent 体系会掩盖新 Runtime 的
 * 能力缺口并继续诱发按场景打补丁。</p>
 */
@Service
public class EvidenceQueryRouter {
    private final AgenticEvidenceFacade agentFacade;

    public EvidenceQueryRouter(EvidenceQueryEngineV3Facade ignoredV3Facade,
                               AgenticEvidenceFacade agentFacade,
                               EvidenceProperties ignoredProperties) {
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
