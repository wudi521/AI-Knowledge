package cn.iocoder.yudao.module.evidence.service;

import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.evidence.controller.admin.evaluate.vo.EvidenceEvaluateRespVO;
import cn.iocoder.yudao.module.evidence.framework.evidence.EvidenceProperties;
import cn.iocoder.yudao.module.evidence.service.assemble.EvidenceAssembler;
import cn.iocoder.yudao.module.evidence.service.assemble.EvidenceDeduplicator;
import cn.iocoder.yudao.module.evidence.service.conflict.ConflictDetector;
import cn.iocoder.yudao.module.evidence.service.generate.AnswerPipeline;
import cn.iocoder.yudao.module.evidence.service.record.EvidenceRecorder;
import cn.iocoder.yudao.module.evidence.service.rule.RuleShortCircuit;
import cn.iocoder.yudao.module.evidence.service.slot.SlotDetector;
import cn.iocoder.yudao.module.evidence.service.sufficiency.SufficiencyJudge;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EvidenceServiceTest {

    @Mock private EvidenceAssembler assembler;
    @Mock private EvidenceDeduplicator deduplicator;
    @Mock private ConflictDetector conflictDetector;
    @Mock private SufficiencyJudge sufficiencyJudge;
    @Mock private AnswerPipeline answerPipeline;
    @Mock private EvidenceRecorder recorder;
    @Mock private SlotDetector slotDetector;
    @Mock private RuleShortCircuit ruleShortCircuit;
    @Mock private KnowledgeApi knowledgeApi;
    @Mock private EvidenceProperties properties;

    private EvidenceService service;
    private MockedStatic<SecurityFrameworkUtils> sf;

    @BeforeEach
    void setUp() {
        service = new EvidenceService();
        ReflectionTestUtils.setField(service, "assembler", assembler);
        ReflectionTestUtils.setField(service, "deduplicator", deduplicator);
        ReflectionTestUtils.setField(service, "conflictDetector", conflictDetector);
        ReflectionTestUtils.setField(service, "sufficiencyJudge", sufficiencyJudge);
        ReflectionTestUtils.setField(service, "answerPipeline", answerPipeline);
        ReflectionTestUtils.setField(service, "recorder", recorder);
        ReflectionTestUtils.setField(service, "slotDetector", slotDetector);
        ReflectionTestUtils.setField(service, "ruleShortCircuit", ruleShortCircuit);
        ReflectionTestUtils.setField(service, "knowledgeApi", knowledgeApi);
        ReflectionTestUtils.setField(service, "properties", properties);
        LoginUser loginUser = new LoginUser();
        loginUser.setId(1L);
        loginUser.setTenantId(1L);
        sf = mockStatic(SecurityFrameworkUtils.class);
        sf.when(SecurityFrameworkUtils::getLoginUser).thenReturn(loginUser);
    }

    @AfterEach
    void tearDown() {
        sf.close();
    }

    @Test
    void ruleHitReturnsAnswerableWithRuleRoute() {
        when(ruleShortCircuit.evaluate(any(), any()))
                .thenReturn(new RuleShortCircuit.RuleConclusion("REGION_SHIPPING", "跨省寄送预计 3 天送达。"));

        EvidenceEvaluateRespVO resp = service.evaluate("跨省寄送要多久?", List.of(), 8);

        assertThat(resp.getAnswerable()).isTrue();
        assertThat(resp.getRoute()).isEqualTo("RULE");
        assertThat(resp.getAnswer()).isEqualTo("跨省寄送预计 3 天送达。");
    }

}
