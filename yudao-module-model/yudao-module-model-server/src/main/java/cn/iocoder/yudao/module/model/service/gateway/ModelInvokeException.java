package cn.iocoder.yudao.module.model.service.gateway;

/**
 * 模型调用异常
 *
 * @param retryable 是否可重试(超时/5xx/网络错=true; 4xx/参数/鉴权/响应解析=false)
 */
public class ModelInvokeException extends RuntimeException {

    private final boolean retryable;

    public ModelInvokeException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public ModelInvokeException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
