package cn.iocoder.yudao.framework.flyway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Flyway 配置(yudao.flyway.*)
 * <p>
 * 多服务单库(所有模块共用 ruoyi-vue-pro): 仅"迁移执行方"启用 enabled=true(建议 yudao-server),
 * 其余模块保持默认关闭, 避免多服务同时 migrate 造成 schema history 竞争。
 * 存量 schema 通过 baselineOnMigrate + baselineVersion 标记, 新迁移从更高版本号开始。
 */
@Data
@ConfigurationProperties(prefix = "yudao.flyway")
public class FlywayProperties {

    /** 是否启用 Flyway 迁移(仅迁移执行方开启) */
    private boolean enabled = false;

    /** 迁移脚本位置(默认 classpath:db/migration) */
    private List<String> locations = new ArrayList<>(List.of("classpath:db/migration"));

    /** schema history 表名 */
    private String table = "flyway_schema_history";

    /** 存量 schema 自动 baseline */
    private boolean baselineOnMigrate = true;

    /** baseline 版本(存量 schema 视为已应用到此版本) */
    private String baselineVersion = "1";

    /** 是否输出迁移日志 */
    private boolean logEnabled = true;

    /** 启动时校验历史迁移 checksum(默认开; 手动迁移过的环境置 false, 否则 NULL checksum 会校验失败) */
    private boolean validateOnMigrate = true;

}
