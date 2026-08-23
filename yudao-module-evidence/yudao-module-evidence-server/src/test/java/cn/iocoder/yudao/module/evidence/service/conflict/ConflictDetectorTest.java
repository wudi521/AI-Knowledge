package cn.iocoder.yudao.module.evidence.service.conflict;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.service.prompt.PromptSupport;
import cn.iocoder.yudao.module.model.api.ModelApi;
import cn.iocoder.yudao.module.model.api.dto.ModelChatReqDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 冲突检测器单测:
 * 1. GENERAL: LLM 输出 conflict=true 但 reason 含“无矛盾/一致”时必须忽略;
 * 2. PATENT: v0.1 整组专利证据跳过通用客服冲突检测，不区分 claim 是否相同。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConflictDetectorTest {

    @Mock
    private ModelApi modelApi;
    @Mock
    private PromptSupport promptSupport;

    private ConflictDetector detector;

    @BeforeEach
    void setUp() {
        detector = new ConflictDetector();
        ReflectionTestUtils.setField(detector, "modelApi", modelApi);
        ReflectionTestUtils.setField(detector, "promptSupport", promptSupport);
        when(promptSupport.get(any(), any())).thenReturn("冲突检测提示词");
    }

    private Evidence evidence(long chunkId, String content, String metadata) {
        return Evidence.builder()
                .chunkId(chunkId)
                .content(content)
                .chunkMetadata(metadata)
                .documentName("doc.pdf")
                .versionNo("V1")
                .build();
    }

    private String patentClaimMeta(String appNo, int claimNo) {
        return JSONUtil.toJsonStr(java.util.Map.of(
                "domainCode", "PATENT", "sectionType", "CLAIMS",
                "applicationNo", appNo, "claimNo", claimNo));
    }

    @Test
    void selfContradictoryTrueWithNoConflictReasonIsIgnored() {
        Evidence a = evidence(1L, "证据一内容", "{\"domainCode\":\"GENERAL\"}");
        Evidence b = evidence(2L, "证据二内容", "{\"domainCode\":\"GENERAL\"}");
        when(modelApi.chat(any(ModelChatReqDTO.class))).thenReturn(CommonResult.success(
                "{\"conflicts\":[{\"pair\":[0,1],\"conflict\":true,\"reason\":\"两者描述一致，无矛盾\"}]}"));

        assertTrue(detector.detect(List.of(a, b)).isEmpty());
    }

    @Test
    void samePatentSameClaimSkipsModelCall() {
        String meta = patentClaimMeta("202311344028.2", 1);
        Evidence a = evidence(1L, "权利要求1第一行", meta);
        Evidence b = evidence(2L, "权利要求1第二行", meta);

        assertTrue(detector.detect(List.of(a, b)).isEmpty());
        verify(modelApi, never()).chat(any(ModelChatReqDTO.class));
    }

    @Test
    void differentPatentClaimsAlsoSkipGenericConflictModel() {
        Evidence a = evidence(1L, "权利要求1内容", patentClaimMeta("202311344028.2", 1));
        Evidence b = evidence(2L, "权利要求2内容", patentClaimMeta("202311344028.2", 2));

        assertTrue(detector.detect(List.of(a, b)).isEmpty());
        verify(modelApi, never()).chat(any(ModelChatReqDTO.class));
    }
}
