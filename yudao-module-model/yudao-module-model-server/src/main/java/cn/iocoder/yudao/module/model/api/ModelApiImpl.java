package cn.iocoder.yudao.module.model.api;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.model.api.dto.ModelChatReqDTO;
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

    @Override
    public CommonResult<String> chat(ModelChatReqDTO req) {
        // 读取本地配置的对话服务(LM Studio, OpenAI 兼容接口)
        String baseUrl = environment.getProperty("yudao.model.chat.base-url", "http://127.0.0.1:1234/v1");
        String modelName = environment.getProperty("yudao.model.chat.model", "qwen/qwen3-8b");
        String url = baseUrl + "/chat/completions";

        Map<String, Object> body = new HashMap<>();
        body.put("model", modelName);
        // 用 HashMap 而非 Map.of: 允许 system/user 为空时仍可发出请求(Map.of 遇 null 抛 NPE)
        List<Map<String, Object>> messages = new ArrayList<>();
        if (req.getSystem() != null) {
            Map<String, Object> systemMsg = new HashMap<>();
            systemMsg.put("role", "system");
            systemMsg.put("content", req.getSystem());
            messages.add(systemMsg);
        }
        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", req.getUser() == null ? "" : req.getUser());
        messages.add(userMsg);
        body.put("messages", messages);
        body.put("temperature", 0.2);
        // 限制生成长度: 抽取/判定输出应是小段 JSON, 不设上限时 CPU 推理(qwen3-8b)可能生成长文导致超时
        body.put("max_tokens", 2048);
        // qwen3 默认开思考模式(thinking): token 全耗在推理上导致 0 输出 + 极慢, 显式关闭
        body.put("chat_template_kwargs", Map.of("enable_thinking", false));
        body.put("thinking", Map.of("type", "disabled"));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);
        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.getBody().get("choices");
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        return success((String) message.get("content"));
    }

}
