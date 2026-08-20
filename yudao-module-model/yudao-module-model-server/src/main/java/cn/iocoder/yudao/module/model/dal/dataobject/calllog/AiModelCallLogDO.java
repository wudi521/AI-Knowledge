package cn.iocoder.yudao.module.model.dal.dataobject.calllog;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * AI 模型调用计量 DO(ai_model_call_log, 网关每次尝试 1 行)
 */
@TableName("ai_model_call_log")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiModelCallLogDO extends TenantBaseDO {

    /** 编号 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 链路追踪号(调用方透传) */
    private String traceId;

    /** 路由场景 */
    private String scenario;

    /** 类型: chat / embedding / rerank */
    private String type;

    /** 命中模型配置编号(yaml 兜底为 NULL) */
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

    /** 计量token(真实usage优先, 否则估算) */
    private Integer promptTokens;

    /** 计量token(真实usage优先, 否则估算) */
    private Integer completionTokens;

    /** 单次尝试耗时 */
    private Integer elapsedMs;

    /** SUCCESS / FAILED / DEGRADED */
    private String status;

    /** 失败原因(截断, 不含密钥) */
    private String errorMsg;

}
