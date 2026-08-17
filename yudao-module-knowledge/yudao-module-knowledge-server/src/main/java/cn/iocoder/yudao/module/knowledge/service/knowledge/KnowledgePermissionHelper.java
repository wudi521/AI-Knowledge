package cn.iocoder.yudao.module.knowledge.service.knowledge;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.AiKnowledgeBaseDO;
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
        if (userId == null || isSuperAdmin(userId)) {
            return true;
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
        Set<String> candidateCodes = kbs.stream()
                .map(AiKnowledgeBaseDO::getVisibleRoles)
                .filter(StrUtil::isNotBlank)
                .flatMap(s -> StrUtil.split(s, ',').stream())
                .map(String::trim).filter(StrUtil::isNotBlank)
                .collect(Collectors.toSet());
        Set<String> myRoles = resolveUserRoles(userId, candidateCodes);
        return kbs.stream().filter(kb -> visibleToUser(kb, myRoles)).toList();
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
