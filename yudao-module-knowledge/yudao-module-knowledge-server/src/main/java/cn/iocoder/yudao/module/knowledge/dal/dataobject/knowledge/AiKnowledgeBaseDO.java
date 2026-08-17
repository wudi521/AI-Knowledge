package cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 知识库 DO(租户隔离: 与 ai_document 一致, 多租户下互不可见)
 */
@TableName("ai_knowledge_base")
@KeySequence("ai_knowledge_base_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiKnowledgeBaseDO extends TenantBaseDO {

    /** 编号 */
    @TableId
    private Long id;
    /** 名称 */
    private String name;
    /** 切分策略: Semantic / ParentChild / Table / FAQ / Policy */
    private String chunkStrategy;
    /** Embedding 模型: BGE-M3 / Qwen */
    private String embedModel;
    /** 状态: 0 停用 1 启用 */
    private Integer status;
    /** 备注 */
    private String remark;
    /** 可见角色 code, 逗号分隔; 空=全部可见 */
    private String visibleRoles;
    /** 有效期至(空=永久) */
    private LocalDateTime effectiveTo;

}
