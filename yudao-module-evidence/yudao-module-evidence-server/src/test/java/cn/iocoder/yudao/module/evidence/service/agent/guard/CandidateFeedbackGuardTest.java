package cn.iocoder.yudao.module.evidence.service.agent.guard;

import cn.iocoder.yudao.module.evidence.domain.Evidence;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CandidateFeedbackGuardTest {
    private final CandidateFeedbackGuard guard = new CandidateFeedbackGuard();

    @Test
    void candidateTitleMustNotBecomeNewHardQueryFact() {
        Evidence candidate = Evidence.builder()
                .documentName("倾转小翼垂直起降固定翼无人机")
                .build();
        List<String> safe = guard.retainSafeQueries(
                "现在专利库里面有名称相近的专利吗？",
                List.of("专利名称 倾转小翼 垂直起降 固定翼 无人机", "名称相似的专利"),
                List.of(candidate));
        assertEquals(List.of("名称相似的专利"), safe);
    }

    @Test
    void titleMentionedByUserMayRemainAsVerifiedAnchor() {
        Evidence candidate = Evidence.builder()
                .documentName("倾转小翼垂直起降固定翼无人机")
                .build();
        List<String> safe = guard.retainSafeQueries(
                "有没有和倾转小翼垂直起降固定翼无人机相似的专利？",
                List.of("倾转小翼垂直起降固定翼无人机 相似专利"),
                List.of(candidate));
        assertEquals(1, safe.size());
    }
}
