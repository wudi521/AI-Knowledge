package cn.iocoder.yudao.module.knowledge.service.knowledge;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.AiKnowledgeBaseDO;
import cn.iocoder.yudao.module.knowledge.service.acl.KnowledgeAclService;
import cn.iocoder.yudao.module.system.api.permission.PermissionApi;
import cn.iocoder.yudao.module.system.enums.permission.RoleCodeEnum;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 知识库可见性助手(角色 code + 有效期)
 */
@Component
public class KnowledgePermissionHelper {

    @Resource
    private PermissionApi permissionApi;
    @Resource
    private cn.iocoder.yudao.module.knowledge.service.acl.KnowledgeAclService aclService;

    /** 超级管理员(system 内置 super_admin 角色)直通 */
    public boolean isSuperAdmin(Long userId) {
        return Boolean.TRUE.equals(permissionApi.hasAnyRoles(userId, RoleCodeEnum.SUPER_ADMIN.getCode()).getCheckedData());
    }

    /** 逐个候选角色 code 判断当前用户是否拥有(每请求候选角色数, 通常 1~5 次 Feign) */
    public Set<String> resolveUserRoles(Long userId, Set<String> candidateCodes) {
        Set<String> result = new HashSet<>();
        for (String code : candidateCodes) {
            if (StrUtil.isNotBlank(code)
                    && Boolean.TRUE.equals(permissionApi.hasAnyRoles(userId, code).getCheckedData())) {
                result.add(code);
            }
        }
        return result;
    }

    /** 知识库对用户是否可见(角色为空=全部可见; 有效期过期=不可见) */
    public boolean visibleToUser(AiKnowledgeBaseDO kb, Set<String> userRoles) {
        if (kb.getEffectiveTo() != null && kb.getEffectiveTo().isBefore(LocalDateTime.now())) {
            return false;
        }
        Set<String> kbRoles = splitRoles(kb.getVisibleRoles());
        if (kbRoles.isEmpty()) {
            return true; // 无角色限制(含空串/纯逗号) -> 全部可见
        }
        return userRoles.stream().anyMatch(kbRoles::contains);
    }

    /** 单知识库对当前用户可见性(详情/上传等单点校验用; userId 为 null 时视为内部调用直通) */
    public boolean isKbVisibleToUser(Long userId, AiKnowledgeBaseDO kb) {
        if (kb == null) {
            return false;
        }
        // D1 分层 ACL: 超管明确绕过(可审计); 无登录态内部调用直通
        if (userId == null || isSuperAdmin(userId)) {
            return true;
        }
        // 显式 ACL 优先(DENY>ALLOW), 无 ACL 记录回退 visible_roles 兼容
        Boolean acl = aclService.isAllowed(userId, KnowledgeAclService.RESOURCE_KB, kb.getId(), KnowledgeAclService.ACTION_READ);
        if (acl != null) {
            return acl;
        }
        Set<String> codes = splitRoles(kb.getVisibleRoles());
        if (codes.isEmpty()) {
            return kb.getEffectiveTo() == null || !kb.getEffectiveTo().isBefore(LocalDateTime.now());
        }
        Set<String> myRoles = resolveUserRoles(userId, codes);
        return visibleToUser(kb, myRoles);
    }

    /** 从知识库列表过滤出当前用户可见的子集(角色解析按全集一次完成) */
    public List<AiKnowledgeBaseDO> filterVisibleKbs(Long userId, List<AiKnowledgeBaseDO> kbs) {
        if (userId == null) {
            return kbs; // 内部调用直通(调用方按 RPC 契约显式传租户)
        }
        if (isSuperAdmin(userId)) {
            return kbs; // 超管明确绕过(D1)
        }
        Set<String> candidateCodes = kbs.stream()
                .map(AiKnowledgeBaseDO::getVisibleRoles)
                .filter(StrUtil::isNotBlank)
                .flatMap(s -> StrUtil.split(s, ',').stream())
                .map(String::trim).filter(StrUtil::isNotBlank)
                .collect(Collectors.toSet());
        Set<String> myRoles = resolveUserRoles(userId, candidateCodes);
        // ① visible_roles 兼容过滤
        List<AiKnowledgeBaseDO> compatible = kbs.stream().filter(kb -> visibleToUser(kb, myRoles)).toList();
        // ② 显式 ACL: 主体级 ALLOW 判定(逐条; DENY 已由 filterDenied 批量移除)
        List<AiKnowledgeBaseDO> aclChecked = new java.util.ArrayList<>();
        for (AiKnowledgeBaseDO kb : compatible) {
            Boolean acl = aclService.isAllowed(userId, KnowledgeAclService.RESOURCE_KB, kb.getId(), KnowledgeAclService.ACTION_READ);
            if (acl == null || acl) {
                aclChecked.add(kb); // 无 ACL 记录或显式 ALLOW
            }
        }
        // ③ 批量移除 ALL 主体 DENY(跨用户黑名单)
        Set<Long> allowedIds = aclService.filterDenied(aclChecked.stream().map(AiKnowledgeBaseDO::getId).collect(Collectors.toSet()));
        return aclChecked.stream().filter(kb -> allowedIds.contains(kb.getId())).toList();
    }

    private Set<String> splitRoles(String visibleRoles) {
        if (StrUtil.isBlank(visibleRoles)) {
            return Set.of();
        }
        return StrUtil.split(visibleRoles, ',').stream()
                .map(String::trim).filter(StrUtil::isNotBlank)
                .collect(Collectors.toSet());
    }

}
