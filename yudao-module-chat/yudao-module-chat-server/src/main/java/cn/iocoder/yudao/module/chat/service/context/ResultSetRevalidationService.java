package cn.iocoder.yudao.module.chat.service.context;

import cn.hutool.core.collection.CollUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.chat.service.context.model.ResultSetSnapshot;
import cn.iocoder.yudao.module.chat.service.context.model.RevalidationResult;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 多轮 ResultSet 重校验。
 *
 * 这里故意不依赖 KnowledgeApi#getDocumentVisibility：当前知识模块的 AiDocumentDO.versionId
 * 是非表字段，selectBatchIds 后可能为空，旧实现会把正常已发布文档全部误判为 STALE_RESULT_SET。
 *
 * 当前安全校验使用两个稳定 Source of Truth：
 * 1. 当前用户仍可见的 KB 集合；
 * 2. 当前 KB 的真实 PUBLISHED documentId 集合（由版本表查询得出）。
 *
 * 以后文档级 ACL 真正落地后，再在这里追加 document ACL，而不是回退到非持久化 versionId。
 */
@Slf4j
@Service
public class ResultSetRevalidationService {

    @Resource
    private KnowledgeApi knowledgeApi;

    public RevalidationResult revalidate(ResultSetSnapshot rs, Long userId, Long kbId, String domainCode) {
        if (rs == null) {
            return RevalidationResult.invalid("STALE_RESULT_SET");
        }
        if (kbId != null && rs.getKbId() != null && !kbId.equals(rs.getKbId())) {
            return RevalidationResult.invalid("DOMAIN_MISMATCH");
        }
        if (domainCode != null && rs.getDomainCode() != null && !domainCode.equals(rs.getDomainCode())) {
            return RevalidationResult.invalid("DOMAIN_MISMATCH");
        }

        // 先校验 KB 仍对当前用户可见。RPC 失败不能把已有上下文静默判 stale，交由调用链继续处理。
        if (userId != null && kbId != null) {
            try {
                CommonResult<Set<Long>> visibleResp = knowledgeApi.getVisibleKbIds(userId);
                Set<Long> visible = visibleResp != null && visibleResp.isSuccess() ? visibleResp.getData() : null;
                if (visible != null && !visible.contains(kbId)) {
                    return RevalidationResult.invalid("PERMISSION_CHANGED");
                }
            } catch (Exception e) {
                log.warn("[revalidate][kbId({}) 用户({}) KB 可见性校验失败, 保留上下文: {}]",
                        kbId, userId, e.getMessage());
            }
        }

        // REF/空集无法逐实体校验，KB/domain 一致即可；实体真正物化时仍由 Structured Query 的 publishedOnly 过滤。
        if (ResultSetSnapshot.STORAGE_REF.equals(rs.getStorageMode()) || CollUtil.isEmpty(rs.getOrderedEntityIds())) {
            return RevalidationResult.valid();
        }

        Long effectiveKbId = kbId != null ? kbId : rs.getKbId();
        if (effectiveKbId == null) {
            return RevalidationResult.invalid("STALE_RESULT_SET");
        }

        try {
            CommonResult<List<Long>> publishedResp = knowledgeApi.getPublishedDocumentIds(effectiveKbId);
            List<Long> published = publishedResp != null && publishedResp.isSuccess() ? publishedResp.getData() : null;
            if (published == null) {
                // RPC 返回不可判定时 fail-open，不能把网络问题错误转成“上一轮结果已过期”。
                return RevalidationResult.valid();
            }
            Set<Long> publishedSet = new HashSet<>(published);
            List<Long> remaining = new ArrayList<>();
            List<Long> removed = new ArrayList<>();
            for (Long id : rs.getOrderedEntityIds()) {
                if (publishedSet.contains(id)) {
                    remaining.add(id);
                } else {
                    removed.add(id);
                }
            }
            if (removed.isEmpty()) {
                return RevalidationResult.valid();
            }
            if (remaining.isEmpty()) {
                return RevalidationResult.invalid("STALE_RESULT_SET");
            }
            return RevalidationResult.partial(remaining, removed);
        } catch (Exception e) {
            log.warn("[revalidate][resultSet({}) 已发布文档校验失败, 保留上下文: {}]",
                    rs.getResultSetId(), e.getMessage());
            return RevalidationResult.valid();
        }
    }
}
