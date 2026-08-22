# 企业级改造 · 01 内部 RPC 认证与 login-user 防伪造(批次 A1)

> 日期: 2026-08-22 · 对应实施规范 A1

## 1. 问题确认

- `TokenAuthenticationFilter.buildLoginUserByHeader` **无条件信任请求的 `login-user` 头**: 业务服务端口被直接访问(绕过网关, 如内网/调试直连 48084 等)时, 攻击者伪造该头即可冒充任意登录用户; 且内部 RPC 路径(`PREFIX/**`)为 permitAll, 无服务身份认证。
- 网关侧已剥离外部 login-user 头(现状), 但仅依赖网关不能覆盖"业务服务直连"场景。

## 2. 方案(轻量版, 与实施规范 HMAC 方向一致)

带 `login-user` 头的请求必须附有效内部签名, 否则视为伪造并剥离:

- **签名**: HMAC-SHA256, 内容 = `appId + "\n" + method + "\n" + path + "\n" + timestamp + "\n" + loginUserValue`
- **请求头**: `X-Internal-App` / `X-Internal-Timestamp` / `X-Internal-Signature`
- **防重放**: 时间窗口 300s(可配); nonce 防重放留待 Redis 化阶段(A1 v2)
- **出站**: Feign 拦截器(`LoginUserRequestInterceptor`)透传 login-user 时同步签名; 网关设置 login-user 头时同步签名
- **入站**: `TokenAuthenticationFilter` 校验; 失败 → 剥离 login-user(按匿名继续) + WARN 日志
- **兼容**: `yudao.security.internal-auth.enabled=false`(默认)时按现状放行; 生产必须 true + 环境变量密钥

## 3. 修改文件

| 文件 | 改动 |
|---|---|
| framework-security `core/rpc/InternalAuthProperties.java`(新) | 内部认证配置(enabled/appId/secretKey/时间窗口) |
| framework-security `core/rpc/InternalAuthService.java`(新) | HMAC 签名生成/校验/时间窗口/appId |
| framework-security `core/filter/TokenAuthenticationFilter.java` | buildLoginUserByHeader 前验签, 失败剥离 |
| framework-security `core/rpc/LoginUserRequestInterceptor.java` | Feign 出站透传 login-user 时同步签名 |
| gateway `filter/security/InternalAuthSigner.java`(新) | 网关出站签名(与业务侧同算法同密钥) |
| gateway `filter/security/TokenAuthenticationFilter.java` | 设置 login-user 头时同步签名头 |
| gateway `util/SecurityFrameworkUtils.java` | encodeLoginUser 抽取 + LOGIN_USER_HEADER 公开 |
| 10 个 AI 模块 `SecurityConfiguration` | actuator 仅放行 health/readiness/liveness; druid 移除匿名放行 |
| 10 个 AI 模块 `application-local.yaml` | internal-auth 配置(环境变量密钥, 默认仅本地) |
| framework-security `pom.xml` + `InternalAuthServiceTest.java`(新) | 测试依赖 + 9 项单测 |

## 4. 配置

```yaml
yudao:
  security:
    internal-auth:
      enabled: true
      secret-key: ${YUDAO_INTERNAL_AUTH_SECRET:dev-internal-secret-2026} # 生产必须环境变量覆盖
      timestamp-window-seconds: 300
```
网关: `yudao.gateway.internal-auth.secret-key`(同一密钥) / `app-id: gateway`

## 5. 验证结果

- 编译: framework-security / gateway / 10 个 AI 模块全部通过(`mvn -o compile`)
- 单测: `InternalAuthServiceTest` 9 项(合法通过/篡改 login-user/篡改签名/篡改 path/过期时间戳/未登记 appId/缺头/未启用兼容/空密钥不误拒), 冒烟实测 **ALL PASSED**
- 验收用例对照(实施规范 A1): 伪造 login-user 头 ✅被剥离 / 过期 timestamp ✅拒绝 / 错误签名 ✅拒绝 / 未登记 appId ✅拒绝 / 合法 Feign 调用 ✅通过

## 6. 状态机/时序

```
[Feign/网关出站] 透传 login-user + 生成签名头
   ↓
[业务服务入站] TokenAuthenticationFilter
   ├─ 无 login-user 头 → 放行(匿名)
   ├─ 有 login-user + enabled=false → 信任(兼容)
   ├─ 有 login-user + enabled=true + 签名有效 → 信任
   └─ 有 login-user + enabled=true + 签名无效/过期 → 剥离, WARN, 按匿名继续
```

## 7. 兼容性、风险与回滚

- **兼容**: enabled 默认 false, 不配置不影响现有调用; 启用后 Feign/网关自动签名, 业务侧零改动
- **风险**: ① 密钥泄漏 → 环境变量管理 + 定期轮换(A2 一并做); ② 多实例时钟偏差 → 300s 窗口已覆盖; ③ 直连不带 login-user 的匿名调用不受影响
- **回滚**: `internal-auth.enabled=false`(或移除配置)即恢复现状; 代码回滚 git revert 对应提交

## 8. 下一子批入口条件

- [x] A1 编译 + 测试通过
- [ ] A2 模型密钥加密(SecretCryptoService AES-256-GCM + 双读单写 + 迁移)
- [ ] A3 文件下载 SSRF/恶意文件防护
- [ ] A4 Flyway 版本化迁移基线
