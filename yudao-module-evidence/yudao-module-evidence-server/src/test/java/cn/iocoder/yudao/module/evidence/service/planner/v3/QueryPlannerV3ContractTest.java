package cn.iocoder.yudao.module.evidence.service.planner.v3;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.evidence.service.prompt.PromptSupport;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainEntityRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainFieldRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.core.DefaultDomainMetricRegistry;
import cn.iocoder.yudao.module.evidence.service.structured.patent.PatentStructuredPack;
import cn.iocoder.yudao.module.model.api.ModelApi;
import cn.iocoder.yudao.module.model.api.dto.ModelChatReqDTO;
import cn.iocoder.yudao.module.model.api.dto.ModelEmbeddingReqDTO;
import cn.iocoder.yudao.module.model.api.dto.ModelRerankReqDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
class QueryPlannerV3ContractTest {

    private RecordingModelApi modelApi;
    private QueryPlannerV3 planner;

    @BeforeEach
    void setUp() {
        DefaultDomainFieldRegistry fields = new DefaultDomainFieldRegistry();
        DefaultDomainMetricRegistry metrics = new DefaultDomainMetricRegistry();
        new PatentStructuredPack(metrics, new DefaultDomainEntityRegistry(), fields);
        QueryIntentValidatorV3 validator = new QueryIntentValidatorV3(fields, metrics);
        modelApi = new RecordingModelApi();
        PromptSupport promptSupport = new PromptSupport() {
            @Override
            public String get(String key, String defaultPrompt) {
                return defaultPrompt;
            }
        };
        planner = new QueryPlannerV3(fields, metrics, modelApi, promptSupport, validator,
                new DeterministicQueryPlannerV3(fields));
    }

    @Test
    void exactIdentifierProjectionNeverCallsModel() {
        QueryIntentV3 intent = planner.plan("申请号 202311832214.0 的公布号是什么？",
                "PATENT", List.of(), List.of(), "trace-1");

        assertThat(intent.getPlannerSource()).isEqualTo("DETERMINISTIC_SCHEMA");
        assertThat(intent.getActions().get(0).getFields()).containsExactly("PUBLICATION_NO");
        assertThat(modelApi.chatCalls).isZero();
    }

    @Test
    void invalidModelPlanBecomesFailedPlanInsteadOfFakeClarification() {
        String invalid = """
                {"selection":{"type":"STRUCTURED_FILTER","field":"TITLE","operator":"GREATER_THAN","values":["磁涌"]},
                 "actions":[{"type":"LIST"}],"requiresClarification":false}
                """;
        modelApi.chatResponse = invalid;

        QueryIntentV3 intent = planner.plan("标题大于磁涌的专利", "PATENT", List.of(), List.of(), "trace-2");

        assertThat(intent.getPlannerStatus()).isEqualTo(QueryIntentV3.PlannerStatus.FAILED);
        assertThat(intent.isRequiresClarification()).isFalse();
        assertThat(intent.getSelection()).isNull();
        assertThat(intent.getActions()).isEmpty();
        assertThat(intent.getReasonCode()).isEqualTo("FILTER_OPERATOR_NOT_ALLOWED_FOR_FIELD");
    }

    private static class RecordingModelApi implements ModelApi {
        private int chatCalls;
        private String chatResponse;

        @Override
        public CommonResult<List<List<Float>>> embedding(List<String> texts) {
            return CommonResult.success(List.of());
        }

        @Override
        public CommonResult<List<List<Float>>> embeddingMeta(ModelEmbeddingReqDTO req) {
            return CommonResult.success(List.of());
        }

        @Override
        public CommonResult<String> chat(ModelChatReqDTO req) {
            chatCalls++;
            return CommonResult.success(chatResponse);
        }

        @Override
        public CommonResult<List<Float>> rerank(ModelRerankReqDTO req) {
            return CommonResult.success(List.of());
        }

        @Override
        public CommonResult<Boolean> hasEnabled(String type) {
            return CommonResult.success(false);
        }
    }
}
