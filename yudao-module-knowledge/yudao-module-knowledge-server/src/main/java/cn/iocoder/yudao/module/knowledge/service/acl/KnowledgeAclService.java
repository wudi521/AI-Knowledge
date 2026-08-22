package cn.iocoder.yudao.module.knowledge.service.acl;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.acl.AiResourceAclDO;
import cn.iocoder.yudao.module.knowledge.dal.mysql.acl.AiResourceAclMapper;
import cn.iocoder.yudao.module.system.api.permission.PermissionApi;
import cn.iocoder.yudao.module.system.enums.permission.RoleCodeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 资源 ACL 判定服务(D1):
 * <ul>
 *   <li>规则: DENY 优先于 ALLOW; 显式 ACL 存在时以其为准, 无记录返回 null(调用方回退 visible_roles 兼容);</li>
 *   <li>主体匹配: ALL 通配 / USER:{id} / ROLE:{code}(解析用户角色) / DEPT:{id} / ORG:{id};</li>
 *   <li>Fail Closed: 判定/解析异常返回"不允许"的保守结果(不泄露); 超管绕过必须显式(isSuperAdmin)。</li>
 * </ul>
 */
@Slf4j
@Service
public class KnowledgeAclService {

    public static final String RESOURCE_KB = "KB";
    public static final String ACTION_READ = "READ";
    public static final String SUBJECT_ALL = "ALL";

    @Resource
    private AiResourceAclMapper aclMapper;
    @Resource
    private PermissionApi permissionApi;

    /**
     * 判定资源动作是否允许(显式 ACL)
     *
     * @return true=允许 / false=拒绝 / null=无 ACL 记录(调用方回退兼容逻辑)
     */
    public Boolean isAllowed(Long userId, String resourceType, Long resourceId, String action) {
        try {
            List<AiResourceAclDO> acls = aclMapper.selectByResource(resourceType, resourceId, action);
            if (acls.isEmpty()) {
                return null;
            }
            Set<String> userRoles = resolveUserRoles(userId);
            boolean effective = false; // 是否有生效的 ALLOW
            for (AiResourceAclDO acl : acls) {
                if (!withinTime(acl)) {
                    continue;
                }
                if (!subjectMatches(acl, userId, userRoles)) {
                    continue;
                }
                effective = true;
                if ("DENY".equalsIgnoreCase(acl.getEffect())) {
                    return false; // DENY 优先
                }
            }
            return effective; // 有生效 ALLOW 则允许, 只有不匹配记录则 null 语义上视为无 ACL
        } catch (Exception e) {
            // Fail Closed: ACL 判定异常保守拒绝(不泄露), 由调用方记日志
            log.error("[isAllowed][ACL 判定异常, 保守拒绝: resource={}:{} action={}]", resourceType, resourceId, action, e);
            return false;
        }
    }

    /** 批量判定知识库可见集(兼容层之后调用: 显式 DENY 移除) */
    public Set<Long> filterDenied(Set<Long> kbIds) {
        Set<Long> allowed = new HashSet<>();
        try {
            List<AiResourceAclDO> acls = aclMapper.selectByResources(RESOURCE_KB, new java.util.ArrayList<>(kbIds), ACTION_READ);
            Map<Long, Boolean> denyMap = new HashMap<>();
            for (AiResourceAclDO acl : acls) {
                if (!withinTime(acl) || !"DENY".equalsIgnoreCase(acl.getEffect())) {
                    continue;
                }
                if (subjectMatches(acl, null, Set.of())) {
                    // 主体 ALL 的 DENY(跨用户): 全拒
                    denyMap.put(acl.getResourceId(), true);
                }
            }
            for (Long id : kbIds) {
                if (!Boolean.TRUE.equals(denyMap.get(id))) {
                    allowed.add(id);
                }
            }
        } catch (Exception e) {
            log.error("[filterDenied][批量 ACL 判定异常, 保守返回空: {}]", e.getMessage());
            return Set.of();
        }
        return allowed;
    }

    /** 单条 ACL 是否对当前用户主体生效(ALL 通配 / USER / ROLE / DEPT / ORG) */
    private boolean subjectMatches(AiResourceAclDO acl, Long userId, Set<String> userRoles) {
        String type = acl.getSubjectType() == null ? "" : acl.getSubjectType().toUpperCase();
        String id = acl.getSubjectId();
        if (SUBJECT_ALL.equals(type)) {
            return true;
        }
        if (userId == null) {
            return false; // 无用户上下文不匹配显式主体(保守)
        }
        switch (type) {
            case "USER" -> {
                return id != null && id.equals(String.valueOf(userId));
            }
            case "ROLE" -> {
                return id != null && userRoles.contains(id);
            }
            case "DEPT", "ORG" -> {
                // 部门/组织: 未接入组织树, 保守按 ALLOW 不匹配(可用 USER/ROLE 配置)
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    /** ACL 是否在生效时间窗内 */
    private boolean withinTime(AiResourceAclDO acl) {
        LocalDateTime now = LocalDateTime.now();
        return (acl.getEffectiveFrom() == null || !acl.getEffectiveFrom().isAfter(now))
                && (acl.getEffectiveTo() == null || !acl.getEffectiveTo().isBefore(now));
    }

    /** 解析用户拥有的角色 code 集(用于 ROLE 主体匹配; 失败返回空, 保守) */
    private Set<String> resolveUserRoles(Long userId) {
        Set<String> result = new HashSet<>();
        if (userId == null) {
            return result;
        }
        try {
            // 逐个枚举角色有成本, 此处仅解析候选(ACL ROLE 主体)
            if (Boolean.TRUE.equals(permissionApi.hasAnyRoles(userId, RoleCodeEnum.SUPER_ADMIN.getCode()).getCheckedData())) {
                result.add(RoleCodeEnum.SUPER_ADMIN.getCode());
            }
            // 其余角色由具体 ACL 主体驱动时再解析(本版支持 SUPER_ADMIN + 后续扩展)
        } catch (Exception e) {
            log.warn("[resolveUserRoles][角色解析失败, 返回空: {}]", e.getMessage());
        }
        return result;
    }
}
