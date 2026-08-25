package cn.iocoder.yudao.module.evidence.service.record;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceRecorderConfidenceTest {

    @Test
    void nullConfidenceMustRemainNullInsteadOfBecomingNumericZero() throws Exception {
        EvidenceRecorder recorder = new EvidenceRecorder();
        Method method = EvidenceRecorder.class.getDeclaredMethod("toConfidence", Double.class);
        method.setAccessible(true);

        Object unknown = method.invoke(recorder, new Object[]{null});
        BigDecimal zero = (BigDecimal) method.invoke(recorder, 0D);
        BigDecimal high = (BigDecimal) method.invoke(recorder, 1.2D);

        assertThat(unknown).isNull();
        assertThat(zero).isEqualByComparingTo("0.0000");
        assertThat(high).isEqualByComparingTo("1.0000");
    }
}
