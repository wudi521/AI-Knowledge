package cn.iocoder.yudao.module.model.service.gateway;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 单次模型调用结果(供计量落库与网关返回)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelCallResult {

    /** 状态: SUCCESS / FAILED / DEGRADED(降级到备选后成功) */
    private String status;
    /** 类型: chat / embedding / rerank */
    private String type;
    /** 命中的模型配置编号(yaml 兜底为 null) */
    private Long modelId;
    /** 模型名快照 */
    private String modelName;
    /** 供应商快照 */
    private String provider;
    /** 第几次尝试 */
    private Integer attempt;
    /** 输入字符数 */
    private Integer promptChars;
    /** 输出字符数 */
    private Integer completionChars;
    /** 计量 token(真实 usage 优先, 否则估算) */
    private Integer promptTokens;
    private Integer completionTokens;
    /** 单次尝试耗时 ms */
    private Integer elapsedMs;
    /** 失败原因(截断, 不含密钥) */
    private String errorMsg;
    /** 链路追踪号(调用方透传, 计量落库用) */
    private String traceId;
    /** 路由场景(计量落库用; 缺省为 *) */
    private String scenario;
    /** 是否成功(SUCCESS/DEGRADED) */
    public boolean isOk() {
        return "SUCCESS".equals(status) || "DEGRADED".equals(status);
    }

    // ===== 载荷(按 type 使用) =====
    /** chat 输出文本 */
    private String chatContent;
    /** embedding 输出向量列表 */
    private List<List<Float>> embeddings;
    /** rerank 输出分数列表(与 documents 对齐) */
    private List<Float> scores;
}
