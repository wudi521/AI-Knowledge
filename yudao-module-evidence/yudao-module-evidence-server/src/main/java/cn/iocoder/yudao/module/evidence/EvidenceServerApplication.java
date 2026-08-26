package cn.iocoder.yudao.module.evidence;

import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 证据平台 Server 启动类。
 *
 * <p>Evidence 通过 knowledge-api 的 Feign 合同访问知识服务。按 API 包扫描，
 * 后续在该包新增 Feign capability/RPC 时无需再逐个修改启动类。</p>
 */
@SpringBootApplication
@EnableFeignClients(basePackageClasses = KnowledgeApi.class)
public class EvidenceServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(EvidenceServerApplication.class, args);
    }

}
