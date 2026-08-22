package cn.iocoder.yudao.framework.flyway.config;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

/**
 * Flyway 版本化数据库迁移自动配置(批次 A4)。
 * <p>
 * 仅当 yudao.flyway.enabled=true(迁移执行方)时生效; 应用启动后执行 migrate:
 * baselineOnMigrate 标记存量 schema(视为已应用至 baselineVersion), 新迁移从更高版本号开始。
 * 迁移失败将阻止应用启动(生产安全: 数据库结构不一致不得启动)。
 */
@Slf4j
@AutoConfiguration
@ConditionalOnProperty(prefix = "yudao.flyway", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(FlywayProperties.class)
public class YudaoFlywayAutoConfiguration {

    @Bean
    public Flyway flyway(DataSource dataSource, FlywayProperties properties) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(properties.getLocations().toArray(new String[0]))
                .table(properties.getTable())
                .baselineOnMigrate(properties.isBaselineOnMigrate())
                .baselineVersion(properties.getBaselineVersion())
                .validateOnMigrate(properties.isValidateOnMigrate())
                .load();
    }

    @Bean
    public ApplicationRunner flywayMigrateRunner(Flyway flyway, FlywayProperties properties) {
        return args -> {
            log.info("[flywayMigrateRunner][开始执行数据库迁移, locations={}]", properties.getLocations());
            try {
                flyway.migrate();
            } catch (Exception e) {
                log.error("[flywayMigrateRunner][数据库迁移失败, 阻止应用启动: {}]", e.getMessage(), e);
                throw e;
            }
        };
    }
}
