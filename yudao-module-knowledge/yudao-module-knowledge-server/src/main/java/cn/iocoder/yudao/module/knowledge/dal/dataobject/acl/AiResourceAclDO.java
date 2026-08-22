package cn.iocoder.yudao.module.knowledge.dal.dataobject.acl;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 企业级资源 ACL(资源级权限: DENY 优先于 ALLOW; 显式 ACL 优先于 visible_roles 兼容)
 */
@TableName("ai_resource_acl")
@Data
@EqualsAndHashCode(callSuper = true)
public class AiResourceAclDO extends TenantBaseDO {

    /** 编号 */
    private Long id;

    /** 资源类型: KB/DOCUMENT/CHUNK/ENTITY */
    private String resourceType;

    /** 资源编号 */
    private Long resourceId;

    /** 主体类型: USER/ROLE/DEPT/ORG/ALL */
    private String subjectType;

    /** 主体编号(ALL 时为空) */
    private String subjectId;

    /** 动作: READ/WRITE/REVIEW/PUBLISH/ADMIN */
    private String action;

    /** 效果: ALLOW/DENY */
    private String effect;

    /** 是否继承父资源(文档继承知识库) */
    private Boolean inherit;

    /** 生效起始(空=永久) */
    private LocalDateTime effectiveFrom;

    /** 生效截止(空=永久) */
    private LocalDateTime effectiveTo;

}
