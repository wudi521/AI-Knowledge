package cn.iocoder.yudao.module.evidence.service.sufficiency;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.domain.Judgement;
import cn.iocoder.yudao.module.evidence.framework.evidence.EvidenceProperties;
import cn.iocoder.yudao.module.evidence.service.validation.DomainEvidenceValidationPolicyRegistry;
import cn.iocoder.yudao.module.evidence.service.validation.patent.PatentEvidenceValidationPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证行业规则通过插件生效，GENERAL 不受 PATENT 策略污染。 */
class SufficiencyJudgeTest {

    private SufficiencyJudge judge;

    @BeforeEach
    void setUp() {
        EvidenceProperties props = new EvidenceProperties();
        EvidenceProperties.Sufficiency sufficiency = new EvidenceProperties.Sufficiency();
        sufficiency.setMinEvidenceCount(2);
        sufficiency.setConflictBlock(true);
        sufficiency.setEntityConsistency(false);
        props.setSufficiency(sufficiency);
        DomainEvidenceValidationPolicyRegistry policies = new DomainEvidenceValidationPolicyRegistry(
                List.of(new PatentEvidenceValidationPolicy()));
        judge = new SufficiencyJudge(props, policies);
    }

    private Evidence evidence(long chunkId, String content, String metadata, double score) {
        return Evidence.builder().chunkId(chunkId).content(content).chunkMetadata(metadata)
                .documentName("doc.pdf").versionNo("V1").score(score).build();
    }

    private String patentMeta(String sectionType) {
        return JSONUtil.toJsonStr(java.util.Map.of(
                "domainCode", "PATENT", "sectionType", sectionType,
                "applicationNo", "202311344028.2"));
    }

    @Test
    void singlePatentClaimEvidenceIsAnswerable() {
        Evidence ev = evidence(1L, "权利要求1：一种分区域视频和图片的储存和下载技术", patentMeta("CLAIMS"), 0.95);
        assertTrue(judge.judge(List.of(ev), List.of(), List.of()).getAnswerable());
    }

    @Test
    void singlePatentBibliographicEvidenceIsAnswerable() {
        Evidence ev = evidence(2L, "(71)申请人 韩信", patentMeta("BIBLIOGRAPHIC"), 0.95);
        assertTrue(judge.judge(List.of(ev), List.of(), List.of()).getAnswerable());
    }

    @Test
    void generalSingleEvidenceKeepsOriginalRule() {
        Evidence ev = evidence(3L, "保修期为一年", "{\"domainCode\":\"GENERAL\"}", 0.9);
        assertFalse(judge.judge(List.of(ev), List.of(), List.of()).getAnswerable());
    }
}
