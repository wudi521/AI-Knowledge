package cn.iocoder.yudao.module.evidence.service.agent;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.evidence.service.agent.capability.CapabilityInvocationContext;
import cn.iocoder.yudao.module.evidence.service.prompt.PromptSupport;
import cn.iocoder.yudao.module.model.api.ModelApi;
import cn.iocoder.yudao.module.model.api.dto.ModelChatReqDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmAgentGoalEvaluatorProofFrontierTest {

    @Test
    void satisfiedEvaluationReturnsOnlyKnownSupportingAndAnswerReferences() {
        LlmAgentGoalEvaluator evaluator = evaluator("""
                {"verdict":"SATISFIED","reason":"新结构化详情已完整证明目标",\
                 "clarificationMessage":null,"supportingReferenceIds":["resolved-plan:detail"],\
                 "answerReferenceIds":["resolved-plan:detail"]}
                """);

        AgentGoalEvaluator.Evaluation result = evaluator.evaluate(
                "查询专利详情",
                List.of(
                        observation("discover-plan:empty", "EMPTY"),
                        observation("resolved-plan:detail", "SUCCESS")
                ),
                List.of("旧空结果", "新详情"),
                List.of(),
                context());

        assertEquals(AgentGoalEvaluator.Verdict.SATISFIED, result.verdict());
        assertEquals(List.of("resolved-plan:detail"), result.supportingReferenceIds());
        assertEquals(List.of("resolved-plan:detail"), result.answerReferenceIds());
    }

    @Test
    void semanticResolutionCanSupportProofWithoutEnteringAnswerContent() {
        LlmAgentGoalEvaluator evaluator = evaluator("""
                {"verdict":"SATISFIED","reason":"语义证据完成实体消歧，结构化结果提供最终详情",\
                 "clarificationMessage":null,\
                 "supportingReferenceIds":["plan:resolve","plan:detail"],\
                 "answerReferenceIds":["plan:detail"]}
                """);

        AgentGoalEvaluator.Evaluation result = evaluator.evaluate(
                "帮我检索出来体替代印花的专利详情信息",
                List.of(
                        observation("plan:resolve", "PARTIAL"),
                        observation("plan:detail", "SUCCESS")
                ), List.of("结构化详情"), List.of(), context());

        assertEquals(AgentGoalEvaluator.Verdict.SATISFIED, result.verdict());
        assertEquals(List.of("plan:resolve", "plan:detail"), result.supportingReferenceIds());
        assertEquals(List.of("plan:detail"), result.answerReferenceIds());
    }

    @Test
    void satisfiedWithoutProofFrontierFailsClosed() {
        LlmAgentGoalEvaluator evaluator = evaluator("""
                {"verdict":"SATISFIED","reason":"声称已经足够",\
                 "clarificationMessage":null,"supportingReferenceIds":[],\
                 "answerReferenceIds":[]}
                """);

        AgentGoalEvaluator.Evaluation result = evaluator.evaluate(
                "查询专利详情",
                List.of(observation("resolved-plan:detail", "SUCCESS")),
                List.of("新详情"), List.of(), context());

        assertEquals(AgentGoalEvaluator.Verdict.EVALUATION_FAILED, result.verdict());
    }

    @Test
    void satisfiedWithoutAnswerReferencesFailsClosed() {
        LlmAgentGoalEvaluator evaluator = evaluator("""
                {"verdict":"SATISFIED","reason":"有证明但没声明答案内容来源",\
                 "clarificationMessage":null,"supportingReferenceIds":["resolved-plan:detail"],\
                 "answerReferenceIds":[]}
                """);

        AgentGoalEvaluator.Evaluation result = evaluator.evaluate(
                "查询专利详情",
                List.of(observation("resolved-plan:detail", "SUCCESS")),
                List.of("新详情"), List.of(), context());

        assertEquals(AgentGoalEvaluator.Verdict.EVALUATION_FAILED, result.verdict());
    }

    @Test
    void inventedProofReferenceFailsClosed() {
        LlmAgentGoalEvaluator evaluator = evaluator("""
                {"verdict":"SATISFIED","reason":"声称已经足够",\
                 "clarificationMessage":null,"supportingReferenceIds":["invented:reference"],\
                 "answerReferenceIds":["invented:reference"]}
                """);

        AgentGoalEvaluator.Evaluation result = evaluator.evaluate(
                "查询专利详情",
                List.of(observation("resolved-plan:detail", "SUCCESS")),
                List.of("新详情"), List.of(), context());

        assertEquals(AgentGoalEvaluator.Verdict.EVALUATION_FAILED, result.verdict());
    }

    @Test
    void answerReferenceOutsideProofFrontierFailsClosed() {
        LlmAgentGoalEvaluator evaluator = evaluator("""
                {"verdict":"SATISFIED","reason":"答案来源不属于证明链",\
                 "clarificationMessage":null,"supportingReferenceIds":["plan:resolve"],\
                 "answerReferenceIds":["plan:detail"]}
                """);

        AgentGoalEvaluator.Evaluation result = evaluator.evaluate(
                "查询专利详情",
                List.of(observation("plan:resolve", "PARTIAL"), observation("plan:detail", "SUCCESS")),
                List.of("详情"), List.of(), context());

        assertEquals(AgentGoalEvaluator.Verdict.EVALUATION_FAILED, result.verdict());
    }

    private LlmAgentGoalEvaluator evaluator(String responseJson) {
        ModelApi modelApi = mock(ModelApi.class);
        PromptSupport promptSupport = mock(PromptSupport.class);
        when(promptSupport.get(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(1));
        when(modelApi.chat(any(ModelChatReqDTO.class))).thenReturn(CommonResult.success(responseJson));
        return new LlmAgentGoalEvaluator(modelApi, promptSupport);
    }

    private AgentObservation observation(String referenceId, String status) {
        return AgentObservation.success("structured_query", "查询详情", "detail", referenceId,
                Map.of(
                        "referenceId", referenceId,
                        "resultStatus", status,
                        "completeDataset", true,
                        "sourceTruncated", false,
                        "missingValueCount", 0));
    }

    private CapabilityInvocationContext context() {
        return new CapabilityInvocationContext(1L, 2L, 6L, "PATENT", "trace-proof-frontier");
    }
}
