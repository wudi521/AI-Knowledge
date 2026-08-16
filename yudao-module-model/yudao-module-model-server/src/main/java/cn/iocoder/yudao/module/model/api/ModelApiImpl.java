package cn.iocoder.yudao.module.model.api;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import jakarta.annotation.Resource;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 模型网关 对外 RPC 实现
 */
@RestController // 提供 RESTful API 接口，给 Feign 调用
@Validated
public class ModelApiImpl implements ModelApi {

    @Resource
    private Environment environment;

    @Resource
    private RestTemplate restTemplate;

    @Override
    public CommonResult<List<List<Float>>> embedding(List<String> texts) {
        // 读取本地配置的 Embedding 服务(LM Studio, OpenAI 兼容接口)
        String baseUrl = environment.getProperty("yudao.model.embedding.base-url", "http://127.0.0.1:1234/v1");
        String modelName = environment.getProperty("yudao.model.embedding.model", "text-embedding-bge-m3");
        String url = baseUrl + "/embeddings";

        Map<String, Object> body = new HashMap<>();
        body.put("model", modelName);
        body.put("input", texts);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);
        List<Map<String, Object>> data = (List<Map<String, Object>>) response.getBody().get("data");
        List<List<Float>> result = new ArrayList<>();
        for (Map<String, Object> item : data) {
            result.add((List<Float>) item.get("embedding"));
        }
        return success(result);
    }

}
