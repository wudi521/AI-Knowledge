package cn.iocoder.yudao.module.model.service.gateway;

/**
 * 模型调用异常
 *
 * <ul>
 *   <li>retryable=true: 瞬态失败(超时/5xx/网络错), 可重试/走熔断;</li>
 *   <li>retryable=false: 非瞬态失败(4xx/参数/响应解析), 不重试;</li>
 *   <li>permanent=true: 永久性配置错误(401/403 鉴权失败), 跳过该候选且不消耗熔断预算
 *       (缺 apiKey 或密钥无效; 修复配置前每次探测都是浪费)。</li>
 * </ul>
 */
public class ModelInvokeException extends RuntimeException {

    private final boolean retryable;

    private final boolean permanent;

    public ModelInvokeException(String message, boolean retryable) {
        this(message, retryable, false, null);
    }

    public ModelInvokeException(String message, boolean retryable, Throwable cause) {
        this(message, retryable, false, cause);
    }

    public ModelInvokeException(String message, boolean retryable, boolean permanent, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.permanent = permanent;
    }

    public boolean isRetryable() {
        return retryable;
    }

    /** 永久性配置错误: 修复配置前无需重试/熔断, 直接跳过该候选 */
    public boolean isPermanent() {
        return permanent;
    }
}
