package cn.iocoder.yudao.module.model.service.gateway;

import cn.iocoder.yudao.module.model.api.dto.ModelChatReqDTO;
import cn.iocoder.yudao.module.model.api.dto.ModelRerankReqDTO;
import cn.iocoder.yudao.module.model.dal.dataobject.model.AiModelConfigDO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;

import jakarta.annotation.Resource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容协议调用器(覆盖 LM Studio / llama.cpp / OpenAI 兼容服务)
 * chat→/chat/completions, embedding→/embeddings, rerank→/rerank
 * 超时/网络错/5xx → ModelInvokeException(retryable=true); 4xx/响应解析失败 → retryable=false
 */
@Component
public class ModelInvoker {

    /** 调用请求(内部统一载荷) */
    public record InvokeRequest(String type, ModelChatReqDTO chatReq, List<String> texts,
                                ModelRerankReqDTO rerankReq) {
    }

    /**
     * 模型调用专用 RestTemplate(自建, 不注册 Bean 避免与框架 loadBalancedRestTemplate 歧义)
     * 带连接/读超时: 模型服务挂死不响应时避免无限阻塞(重试/降级/熔断才有效)
     */
    private RestTemplate restTemplate;

    @Value("${yudao.model.connect-timeout-ms:5000}")
    private int connectTimeoutMs;

    @Value("${yudao.model.read-timeout-ms:300000}")
    private int readTimeoutMs;

    @PostConstruct
    public void initRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * 调用注册表中的模型配置
     */
    public ModelCallResult invoke(AiModelConfigDO cfg, InvokeRequest req) {
        return doCall(req.type(), cfg.getBaseUrl(), cfg.getModelName(), cfg.getApiKey(), cfg, req);
    }

    /**
     * 调用 yaml 默认目标(modelId=null)
     */
    public ModelCallResult invokeYaml(YamlModelDefaults.ModelTarget target, InvokeRequest req) {
        return doCall(target.type(), target.baseUrl(), target.modelName(), null, null, req);
    }

    private ModelCallResult doCall(String type, String baseUrl, String modelName, String apiKey,
                                   AiModelConfigDO cfg, InvokeRequest req) {
        long start = System.currentTimeMillis();
        try {
            ModelCallResult r = switch (type) {
                case "chat" -> callChat(baseUrl, modelName, apiKey, req.chatReq());
                case "embedding" -> callEmbedding(baseUrl, modelName, apiKey, req.texts());
                case "rerank" -> callRerank(baseUrl, modelName, apiKey, req.rerankReq());
                default -> throw new ModelInvokeException("未知模型类型: " + type, false);
            };
            r.setType(type);
            r.setModelId(cfg != null ? cfg.getId() : null);
            r.setModelName(modelName);
            r.setProvider(cfg != null ? cfg.getProvider() : null);
            r.setElapsedMs((int) (System.currentTimeMillis() - start));
            return r;
        } catch (ModelInvokeException e) {
            throw e;
        } catch (RestClientException e) {
            // 超时/连接失败/5xx: ResourceAccessException 是网络/超时; 其余 RestClientException 含 5xx(spring 抛 HttpServerErrorException)
            boolean retryable = !(e instanceof org.springframework.web.client.HttpClientErrorException);
            throw new ModelInvokeException("模型调用失败: " + safeMsg(e), retryable, e);
        } catch (Exception e) {
            throw new ModelInvokeException("模型调用异常: " + safeMsg(e), false, e);
        }
    }

    private ModelCallResult callChat(String baseUrl, String modelName, String apiKey, ModelChatReqDTO req) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", modelName);
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
        body.put("temperature", req.getTemperature() != null ? req.getTemperature() : 0.2);
        body.put("max_tokens", 2048);
        body.put("chat_template_kwargs", Map.of("enable_thinking", false));
        body.put("thinking", Map.of("type", "disabled"));
        body.put("reasoning_effort", "none");

        Map resp = post(baseUrl + "/chat/completions", body, apiKey);
        List<Map<String, Object>> choices = (List<Map<String, Object>>) resp.get("choices");
        if (choices == null || choices.isEmpty() || choices.get(0).get("message") == null) {
            throw new ModelInvokeException("chat 响应无 choices", false);
        }
        String content = (String) ((Map<String, Object>) choices.get(0).get("message")).get("content");
        int promptChars = charsOf(req.getSystem()) + charsOf(req.getUser());
        int completionChars = charsOf(content);
        Map<String, Object> usage = (Map<String, Object>) resp.get("usage");
        return ModelCallResult.builder().chatContent(content)
                .promptChars(promptChars).completionChars(completionChars)
                .promptTokens(usage != null && usage.get("prompt_tokens") != null
                        ? ((Number) usage.get("prompt_tokens")).intValue() : estTokens(promptChars))
                .completionTokens(usage != null && usage.get("completion_tokens") != null
                        ? ((Number) usage.get("completion_tokens")).intValue() : estTokens(completionChars))
                .build();
    }

    private ModelCallResult callEmbedding(String baseUrl, String modelName, String apiKey, List<String> texts) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", modelName);
        body.put("input", texts);
        Map resp = post(baseUrl + "/embeddings", body, apiKey);
        List<Map<String, Object>> data = (List<Map<String, Object>>) resp.get("data");
        if (data == null) {
            throw new ModelInvokeException("embedding 响应无 data", false);
        }
        List<List<Float>> result = new ArrayList<>();
        for (Map<String, Object> item : data) {
            result.add((List<Float>) item.get("embedding"));
        }
        int promptChars = texts == null ? 0 : texts.stream().mapToInt(this::charsOf).sum();
        return ModelCallResult.builder().embeddings(result)
                .promptChars(promptChars).completionChars(0)
                .promptTokens(estTokens(promptChars)).completionTokens(0)
                .build();
    }

    private ModelCallResult callRerank(String baseUrl, String modelName, String apiKey, ModelRerankReqDTO req) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", modelName);
        body.put("query", req.getQuery());
        body.put("documents", req.getDocuments());
        Map resp = post(baseUrl + "/rerank", body, apiKey);
        List<Map<String, Object>> results = (List<Map<String, Object>>) resp.get("results");
        if (results == null) {
            throw new ModelInvokeException("rerank 响应无 results: " + resp.get("error"), false);
        }
        Float[] scores = new Float[req.getDocuments().size()];
        for (Map<String, Object> r : results) {
            int idx = ((Number) r.get("index")).intValue();
            scores[idx] = ((Number) r.get("relevance_score")).floatValue();
        }
        List<Float> list = new ArrayList<>();
        for (Float s : scores) {
            list.add(s == null ? 0F : s);
        }
        int promptChars = charsOf(req.getQuery()) + (req.getDocuments() == null ? 0
                : req.getDocuments().stream().mapToInt(this::charsOf).sum());
        return ModelCallResult.builder().scores(list)
                .promptChars(promptChars).completionChars(0)
                .promptTokens(estTokens(promptChars)).completionTokens(0)
                .build();
    }

    private Map post(String url, Map<String, Object> body, String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.isBlank()) {
            headers.setBearerAuth(apiKey);
        }
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);
        Map resp = response.getBody();
        if (resp == null) {
            throw new ModelInvokeException("模型响应为空", false);
        }
        return resp;
    }

    private int charsOf(String s) {
        return s == null ? 0 : s.length();
    }

    /** token 估算: ceil(chars/1.5)(中文为主的经验值; 真实 usage 优先) */
    private int estTokens(int chars) {
        return (int) Math.ceil(chars / 1.5);
    }

    private String safeMsg(Throwable e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : (m.length() > 200 ? m.substring(0, 200) : m);
    }
}
