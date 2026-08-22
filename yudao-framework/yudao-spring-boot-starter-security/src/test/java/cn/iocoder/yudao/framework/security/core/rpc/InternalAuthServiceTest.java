package cn.iocoder.yudao.framework.security.core.rpc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内部 RPC 认证(login-user 防伪造)单元测试
 */
class InternalAuthServiceTest {

    private static final String SECRET = "test-secret-123";
    private static final String LOGIN_USER = "%7B%22id%22%3A1%7D";

    private InternalAuthService service;

    @BeforeEach
    void setUp() throws Exception {
        InternalAuthProperties props = new InternalAuthProperties();
        props.setEnabled(true);
        props.setSecretKey(SECRET);
        props.setAppId("ingestion");
        props.setTimestampWindowSeconds(300);
        service = new InternalAuthService();
        Field f = InternalAuthService.class.getDeclaredField("properties");
        f.setAccessible(true);
        f.set(service, props);
    }

    @Test
    void validSignature_passes() {
        String ts = service.currentTimestamp();
        String sig = service.sign("ingestion", "POST", "/admin-api/knowledge/get-document", ts, LOGIN_USER);
        assertTrue(service.verify("ingestion", "POST", "/admin-api/knowledge/get-document", ts, LOGIN_USER, sig));
    }

    @Test
    void tamperedLoginUser_rejected() {
        String ts = service.currentTimestamp();
        String sig = service.sign("ingestion", "POST", "/admin-api/knowledge/get-document", ts, LOGIN_USER);
        assertFalse(service.verify("ingestion", "POST", "/admin-api/knowledge/get-document", ts, LOGIN_USER + "x", sig));
    }

    @Test
    void tamperedSignature_rejected() {
        String ts = service.currentTimestamp();
        String sig = service.sign("ingestion", "POST", "/admin-api/knowledge/get-document", ts, LOGIN_USER);
        assertFalse(service.verify("ingestion", "POST", "/admin-api/knowledge/get-document", ts, LOGIN_USER, sig + "0"));
    }

    @Test
    void tamperedPath_rejected() {
        String ts = service.currentTimestamp();
        String sig = service.sign("ingestion", "POST", "/admin-api/knowledge/get-document", ts, LOGIN_USER);
        assertFalse(service.verify("ingestion", "POST", "/admin-api/knowledge/other", ts, LOGIN_USER, sig));
    }

    @Test
    void expiredTimestamp_rejected() {
        String oldTs = String.valueOf(Long.parseLong(service.currentTimestamp()) - 600);
        String oldSig = service.sign("ingestion", "POST", "/admin-api/knowledge/get-document", oldTs, LOGIN_USER);
        assertFalse(service.verify("ingestion", "POST", "/admin-api/knowledge/get-document", oldTs, LOGIN_USER, oldSig));
    }

    @Test
    void unknownAppId_rejected() {
        String ts = service.currentTimestamp();
        String sig = service.sign("ingestion", "POST", "/admin-api/knowledge/get-document", ts, LOGIN_USER);
        assertFalse(service.verify("hacker", "POST", "/admin-api/knowledge/get-document", ts, LOGIN_USER, sig));
    }

    @Test
    void missingHeaders_rejected() {
        String ts = service.currentTimestamp();
        String sig = service.sign("ingestion", "POST", "/admin-api/knowledge/get-document", ts, LOGIN_USER);
        assertFalse(service.verify(null, "POST", "/admin-api/knowledge/get-document", ts, LOGIN_USER, sig));
        assertFalse(service.verify("ingestion", "POST", "/admin-api/knowledge/get-document", null, LOGIN_USER, sig));
    }

    @Test
    void disabled_authCompatPasses() throws Exception {
        InternalAuthProperties props = new InternalAuthProperties();
        props.setEnabled(false);
        InternalAuthService s = new InternalAuthService();
        Field f = InternalAuthService.class.getDeclaredField("properties");
        f.setAccessible(true);
        f.set(s, props);
        assertTrue(s.verify("anything", "POST", "/x", "0", "any", "any"), "未启用时兼容放行");
        assertFalse(s.isReady());
    }

    @Test
    void enabledWithoutSecret_notReady() throws Exception {
        InternalAuthProperties props = new InternalAuthProperties();
        props.setEnabled(true);
        props.setSecretKey("");
        InternalAuthService s = new InternalAuthService();
        Field f = InternalAuthService.class.getDeclaredField("properties");
        f.setAccessible(true);
        f.set(s, props);
        assertFalse(s.isReady(), "启用但空密钥时按未启用处理, 避免误拒绝");
    }
}
