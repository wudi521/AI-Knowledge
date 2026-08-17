package cn.iocoder.yudao.module.knowledge.service.knowledge;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.AiKnowledgeBaseDO;
import cn.iocoder.yudao.module.system.api.permission.PermissionApi;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 知识库可见性助手(角色 code + 有效期)
 */
@Component
public class KnowledgePermissionHelper {

    @Resource
    private PermissionApi permissionApi;

    /** 超级管理员(system 内置 super_admin 角色)直通 */
    public boolean isSuperAdmin(Long userId) {
        return Boolean.TRUE.equals(permissionApi.hasAnyRoles(userId, "super_admin").getCheckedData());
    }

    /** 逐个候选角色 code 判断当前用户是否拥有(每请求仅知识库页内角色数, 通常 1~5 次 Feign) */
    public Set<String> resolveUserRoles(Long userId, Set<String> candidateCodes) {
        Set<String> result = new HashSet<>();
        for (String code : candidateCodes) {
            if (Boolean.TRUE.equals(permissionApi.hasAnyRoles(userId, code).getCheckedData())) {
                result.add(code);
            }
        }
        return result;
    }

    /** 知识库对用户是否可见(空角色=全部可见; 有效期过期=不可见) */
    public boolean visibleToUser(AiKnowledgeBaseDO kb, Set<String> userRoles) {
        if (kb.getEffectiveTo() != null && kb.getEffectiveTo().isBefore(LocalDateTime.now())) {
            return false;
        }
        if (StrUtil.isBlank(kb.getVisibleRoles())) {
            return true;
        }
        Set<String> kbRoles = StrUtil.split(kb.getVisibleRoles(), ',').stream()
                .map(String::trim).filter(StrUtil::isNotBlank).collect(Collectors.toSet());
        return userRoles.stream().anyMatch(kbRoles::contains);
    }

}
