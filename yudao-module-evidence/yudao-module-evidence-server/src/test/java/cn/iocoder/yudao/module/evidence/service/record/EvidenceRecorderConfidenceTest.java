package cn.iocoder.yudao.module.evidence.service.record;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceRecorderConfidenceTest {

    @Test
    void evalUnknownMustRemainNullWhileEvidenceScoreKeepsNotNullDefault() throws Exception {
        EvidenceRecorder recorder = new EvidenceRecorder();
        Method evalMethod = EvidenceRecorder.class.getDeclaredMethod("toNullableConfidence", Double.class);
        Method evidenceMethod = EvidenceRecorder.class.getDeclaredMethod("toEvidenceConfidence", Double.class);
        evalMethod.setAccessible(true);
        evidenceMethod.setAccessible(true);

        Object unknownEval = evalMethod.invoke(recorder, new Object[]{null});
        BigDecimal missingEvidenceScore = (BigDecimal) evidenceMethod.invoke(recorder, new Object[]{null});
        BigDecimal highEval = (BigDecimal) evalMethod.invoke(recorder, 1.2D);

        assertThat(unknownEval).isNull();
        assertThat(missingEvidenceScore).isEqualByComparingTo("0.0000");
        assertThat(highEval).isEqualByComparingTo("1.0000");
    }
}
