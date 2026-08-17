package cn.iocoder.yudao.module.knowledge.service.conflict;

import cn.iocoder.yudao.module.knowledge.dal.dataobject.conflict.ConflictDO;

import java.util.List;

public interface ConflictService {

    /**
     * 发布前冲突检测(规则粗筛 + LLM 判定):
     * 新版本必审条目 vs 旧已发布版本条目, 同主题 + 文本差异大 -> 候选 -> LLM 判定冲突 -> 落库 PENDING
     *
     * @param versionId 新版本编号
     * @return 检测出的冲突数
     */
    int detectConflicts(Long versionId);

    /** 版本下是否存在待裁决冲突(发布门禁用) */
    boolean hasPendingConflicts(Long versionId);

    /** 查询文档冲突列表 */
    List<ConflictDO> getConflictList(Long docId, String status);

    /**
     * 裁决: RESOLVED_NEW(以新版为准) -> 解除; RESOLVED_OLD(以旧版为准) -> 解除并驳回关联条目
     */
    void resolve(Long conflictId, String resolveType, String comment);

}
