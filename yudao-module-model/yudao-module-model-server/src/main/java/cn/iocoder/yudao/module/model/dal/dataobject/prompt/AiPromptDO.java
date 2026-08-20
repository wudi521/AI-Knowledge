package cn.iocoder.yudao.module.model.dal.dataobject.prompt;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * AI Prompt DO(平台级表, 非租户表, 业务键内多版本)
 */
@TableName("ai_prompt")
@KeySequence("ai_prompt_seq")
@TenantIgnore // 平台级表: 不参与租户过滤(拦截器对无 @TenantIgnore 的 BaseDO 表也会加 tenant_id)
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiPromptDO extends BaseDO {

    /** 编号 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 业务键(如 query-analysis/slot-detect) */
    private String promptKey;
    /** 名称 */
    private String name;
    /** 说明 */
    private String description;
    /** 提示词内容 */
    private String content;
    /** 版本号(同 key 自增) */
    private Integer version;
    /** 状态: 0=停用 1=启用(全量) 2=灰度中(带 grayTenantIds) */
    private Integer status;
    /** 灰度租户列表(JSON 数组) */
    private String grayTenantIds;

}
