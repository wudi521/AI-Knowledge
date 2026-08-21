package cn.iocoder.yudao.module.model.service.gateway;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.module.model.api.dto.ModelChatReqDTO;
import cn.iocoder.yudao.module.model.api.dto.ModelRerankReqDTO;
import cn.iocoder.yudao.module.model.dal.dataobject.model.AiModelConfigDO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 模型网关编排: 路由 → 逐候选(熔断+重试) → yaml 兜底 → 计量 → 返回
 * 失败语义: 全部失败抛 ServiceException(调用方按现状降级); 计量不阻断
 */
@Slf4j
@Component
public class ModelGateway {

    @Resource
    private ModelResolver resolver;
    @Resource
    private ModelInvoker invoker;
    @Resource
    private RetryPolicy retryPolicy;
    @Resource
    private CircuitBreaker circuitBreaker;
    @Resource
    private CallMeter meter;

    /** chat(显式场景/追踪号; 缺省走默认路由); 带图片时自动路由到 image 类型视觉模型 */
    public String chat(ModelChatReqDTO req, String scenario, String traceId) {
        String type = (req.getImages() != null && !req.getImages().isEmpty()) ? "image" : "chat";
        ModelCallResult r = invokeWithFallback(type, scenario, traceId,
                new ModelInvoker.InvokeRequest(type, req, null, null));
        return r.getChatContent();
    }

    /** embedding(显式场景/追踪号) */
    public List<List<Float>> embedding(List<String> texts, String scenario, String traceId) {
        ModelCallResult r = invokeWithFallback("embedding", scenario, traceId,
                new ModelInvoker.InvokeRequest("embedding", null, texts, null));
        return r.getEmbeddings();
    }

    /** rerank(显式场景/追踪号) */
    public List<Float> rerank(ModelRerankReqDTO req, String scenario, String traceId) {
        ModelCallResult r = invokeWithFallback("rerank", scenario, traceId,
                new ModelInvoker.InvokeRequest("rerank", null, null, req));
        return r.getScores();
    }

    /** 指定类型是否存在启用模型(图片理解等调用方探测) */
    public boolean hasEnabled(String type) {
        List<AiModelConfigDO> candidates = resolver.resolveCandidates(type, null);
        return candidates != null && !candidates.isEmpty();
    }

    private ModelCallResult invokeWithFallback(String type, String scenario, String traceId,
                                               ModelInvoker.InvokeRequest req) {
        List<AiModelConfigDO> candidates = resolver.resolveCandidates(type, scenario);
        boolean degraded = false;
        ModelCallResult lastFailure = null;
        if (candidates != null) {
            for (AiModelConfigDO cfg : candidates) {
                String key = type + ":" + cfg.getId();
                if (!circuitBreaker.tryAcquire(key)) {
                    continue;
                }
                int attempts = retryPolicy.attempts(true);
                for (int attempt = 1; attempt <= attempts; attempt++) {
                    long t0 = System.currentTimeMillis();
                    try {
                        ModelCallResult r = invoker.invoke(cfg, req);
                        r.setAttempt(attempt);
                        r.setElapsedMs((int) (System.currentTimeMillis() - t0));
                        r.setStatus(degraded ? "DEGRADED" : "SUCCESS");
                        r.setTraceId(traceId);
                        r.setScenario(scenario == null ? "*" : scenario);
                        circuitBreaker.onSuccess(key);
                        meter.record(r);
                        return r;
                    } catch (ModelInvokeException e) {
                        ModelCallResult failed = buildFailure(type, cfg, attempt, traceId, scenario, degraded, e);
                        failed.setElapsedMs((int) (System.currentTimeMillis() - t0));
                        meter.record(failed);
                        lastFailure = failed;
                        // 永久性配置错误(401/403 鉴权失败): 跳过该候选且不消耗瞬态熔断预算,
                        // 修复配置前无需反复探测; 其余失败正常走熔断
                        if (e.isPermanent()) {
                            log.warn("[invoke][模型 {} 永久性失败(鉴权/配置), 跳过该候选: {}]",
                                    cfg.getName(), cn.hutool.core.util.StrUtil.maxLength(e.getMessage(), 200));
                            break;
                        }
                        circuitBreaker.onFailure(key);
                        if (!e.isRetryable() || attempt >= attempts) {
                            break;
                        }
                        retryPolicy.backoff(attempt);
                    }
                }
                degraded = true; // 下一个候选为降级
            }
        }
        // 配置全部来自数据库(ai_model_config): 无候选或全部失败 → 明确报错
        String msg;
        if (lastFailure != null && lastFailure.getErrorMsg() != null) {
            msg = lastFailure.getErrorMsg();
        } else {
            msg = (candidates != null && !candidates.isEmpty())
                    ? "模型候选全部被熔断跳过" : "未配置可用的 " + type + " 模型, 请在模型管理页配置";
        }
        log.warn("[invokeWithFallback][type({}) 模型调用全部失败: {}]", type, msg);
        throw new ServiceException(GlobalErrorCodeConstants.INTERNAL_SERVER_ERROR.getCode(), "模型调用失败: " + msg);
    }

    private ModelCallResult buildFailure(String type, AiModelConfigDO cfg, int attempt, String traceId,
                                         String scenario, boolean degraded, ModelInvokeException e) {
        // 失败的尝试一律 FAILED(降级语义只属于成功路径: 见 ModelCallResult.isOk)
        return ModelCallResult.builder()
                .status("FAILED")
                .type(type)
                .modelId(cfg != null ? cfg.getId() : null)
                .modelName(cfg != null ? cfg.getModelName() : null)
                .provider(cfg != null ? cfg.getProvider() : null)
                .attempt(attempt)
                .elapsedMs(0)
                .errorMsg(truncate(e.getMessage()))
                .traceId(traceId)
                .scenario(scenario == null ? "*" : scenario)
                .build();
    }

    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
