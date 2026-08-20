package cn.iocoder.yudao.module.model.service.gateway;

import cn.iocoder.yudao.module.model.dal.dataobject.calllog.AiModelCallLogDO;
import cn.iocoder.yudao.module.model.dal.mysql.calllog.AiModelCallLogMapper;
import com.alibaba.ttl.TtlRunnable;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 调用计量: 异步落 ai_model_call_log(单线程守护池 + TtlRunnable 传租户), 失败不阻断调用
 */
@Slf4j
@Component
public class CallMeter {

    private static final ExecutorService ASYNC_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "model-call-meter");
        t.setDaemon(true);
        return t;
    });

    @Resource
    private AiModelCallLogMapper mapper;

    public void record(ModelCallResult r) {
        if (r == null) {
            return;
        }
        ASYNC_EXECUTOR.execute(TtlRunnable.get(() -> {
            try {
                AiModelCallLogDO log = new AiModelCallLogDO();
                log.setTraceId(r.getTraceId());
                log.setScenario(r.getScenario());
                log.setType(r.getType());
                log.setModelId(r.getModelId());
                log.setModelName(r.getModelName());
                log.setProvider(r.getProvider());
                log.setAttempt(r.getAttempt() == null ? 1 : r.getAttempt());
                log.setPromptChars(r.getPromptChars() == null ? 0 : r.getPromptChars());
                log.setCompletionChars(r.getCompletionChars() == null ? 0 : r.getCompletionChars());
                log.setPromptTokens(r.getPromptTokens() == null ? 0 : r.getPromptTokens());
                log.setCompletionTokens(r.getCompletionTokens() == null ? 0 : r.getCompletionTokens());
                log.setElapsedMs(r.getElapsedMs() == null ? 0 : r.getElapsedMs());
                log.setStatus(r.getStatus());
                log.setErrorMsg(truncate(r.getErrorMsg()));
                mapper.insert(log);
            } catch (Exception e) {
                log.warn("[record][模型计量落库失败, 忽略: {}]", e.getMessage());
            }
        }));
    }

    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
