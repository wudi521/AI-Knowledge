package cn.iocoder.yudao.module.evidence;

import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.EnableFeignClients;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceServerApplicationFeignTest {

    @Test
    void evidenceServerMustScanKnowledgeApiPackageForFeignContracts() {
        EnableFeignClients annotation = EvidenceServerApplication.class.getAnnotation(EnableFeignClients.class);

        assertThat(annotation).as("evidence-server must enable Feign client registration").isNotNull();
        assertThat(annotation.basePackageClasses()).contains(KnowledgeApi.class);
    }
}
