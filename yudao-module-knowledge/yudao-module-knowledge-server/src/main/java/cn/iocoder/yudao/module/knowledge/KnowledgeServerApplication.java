package cn.iocoder.yudao.module.knowledge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 知识平台 Server 启动类
 */
@SpringBootApplication
@EnableScheduling // Outbox 定时补偿
public class KnowledgeServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(KnowledgeServerApplication.class, args);
    }

}
