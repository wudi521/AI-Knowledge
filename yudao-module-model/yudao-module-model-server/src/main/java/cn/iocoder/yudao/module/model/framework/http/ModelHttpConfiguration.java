package cn.iocoder.yudao.module.model.framework.http;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * 模型调用专用 RestTemplate(带连接/读超时)
 * <p>
 * 背景: 框架公共 RestTemplate 未配读超时, 模型服务挂死(不响应)时调用线程将无限阻塞,
 * 重试/降级/熔断全部失效。本 Bean 供 ModelInvoker 使用(@Qualifier("modelRestTemplate"))。
 */
@Configuration(value = "modelHttpConfiguration", proxyBeanMethods = false)
public class ModelHttpConfiguration {

    @Value("${yudao.model.connect-timeout-ms:5000}")
    private int connectTimeoutMs;

    @Value("${yudao.model.read-timeout-ms:300000}")
    private int readTimeoutMs;

    @Bean("modelRestTemplate")
    public RestTemplate modelRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return new RestTemplate(factory);
    }

}
