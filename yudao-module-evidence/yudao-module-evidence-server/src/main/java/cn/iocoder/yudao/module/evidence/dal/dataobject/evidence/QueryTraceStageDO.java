package cn.iocoder.yudao.module.evidence.dal.dataobject.evidence;

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

/** 持久化 Query/Agent 可审计步骤，用于按 traceId 事后回放。 */
@TableName("ai_query_trace_stage")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryTraceStageDO extends TenantBaseDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String traceId;
    private Integer seq;
    private String stage;
    private String status;
    private Long elapsedMs;
    private String errorCode;
    private String errorMessage;
    private String inputSummary;
    private String outputSummary;
}
