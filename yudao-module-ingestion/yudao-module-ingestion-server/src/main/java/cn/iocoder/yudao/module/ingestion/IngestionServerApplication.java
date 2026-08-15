package cn.iocoder.yudao.module.ingestion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 入库管线 Server 启动类
 */
@SpringBootApplication
public class IngestionServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(IngestionServerApplication.class, args);
    }

}
