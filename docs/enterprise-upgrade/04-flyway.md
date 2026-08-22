# 企业级改造 · 04 Flyway 版本化迁移体系(批次 A4)

> 日期: 2026-08-22 · 对应实施规范 A4

## 1. 问题确认
- 项目无版本化迁移: 变更靠 `sql/` 手写脚本 + 各模块 `resources/sql/` 手工执行,
  不可重复部署、无 schema history、多服务无 owner 归属。

## 2. 方案(Flyway 7.15 手动集成)
- 新 starter `yudao-spring-boot-starter-flyway`:
  - `YudaoFlywayAutoConfiguration`(@ConditionalOnProperty yudao.flyway.enabled=true)
  - baselineOnMigrate=true + baselineVersion=1: 存量 schema 视为已应用至 V1, 新迁移从 V2 起
  - 迁移失败阻止应用启动(结构不一致不得上线)
- **多服务单库**(所有模块共用 ruoyi-vue-pro): 仅迁移执行方(yudao-server)启用,
  其余模块保持默认关闭, 避免多服务同时 migrate 的 schema history 竞争
- 迁移目录: `yudao-server/src/main/resources/db/migration/`(V2__ 起)
- 历史手动迁移(`sql/migrate-*.sql`)保留为"已执行基线", 不回填 Flyway(由 baseline 覆盖)

## 3. 文件
- `yudao-framework/yudao-spring-boot-starter-flyway/`(新, pom+autoconfig+properties+imports)
- `yudao-dependencies/pom.xml` + `yudao-framework/pom.xml`(module 注册)
- `yudao-server/pom.xml` + `application-local.yaml`(flyway.enabled=true)
- `yudao-server/src/main/resources/db/migration/`(迁移目录)

## 4. 配置
```yaml
yudao:
  flyway:
    enabled: true   # 仅迁移执行方(yudao-server)开启
    locations: classpath:db/migration
    baseline-on-migrate: true
    baseline-version: "1"
```

## 5. 验证
- 编译通过(flyway starter + yudao-server)
- ⚠️ 连库实测受沙箱网络限制未完成(Java 直连 3306 被拦); 部署后 yudao-server 首次启动将
  baseline + 创建 flyway_schema_history, 后续迁移从 V2 起(见 B 批)

## 6. 回滚
- 移除 `yudao.flyway.enabled=true` 即跳过迁移; 已执行迁移记录在 schema history, 回滚按迁移脚本说明
