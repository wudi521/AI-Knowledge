package cn.iocoder.yudao.module.knowledge.service.version;

import cn.iocoder.yudao.module.knowledge.dal.dataobject.version.AiDocVersionDO;

import java.util.List;

public interface AiDocVersionService {

    /** 创建文档的下一版本(versionNo 自动 V1/V2/...), 状态 DRAFT */
    AiDocVersionDO createVersion(Long docId);

    AiDocVersionDO getVersion(Long id);

    /** 最新版本(含草稿) */
    AiDocVersionDO getLatestVersion(Long docId);

    /** 当前已发布版本(检索口径, 同文档仅一个) */
    AiDocVersionDO getPublishedVersion(Long docId);

    List<AiDocVersionDO> getVersionList(Long docId);

    /** 按编号批量查询版本(Feign 联表用, 不存在自动过滤) */
    List<AiDocVersionDO> getVersionListByIds(java.util.Collection<Long> versionIds);

    /** 提交审核: DRAFT -> REVIEW */
    void submitForReview(Long versionId);

    /**
     * 发布(门禁: 状态合法 + 无未处理必审条目 + 无待裁决冲突)
     * 通过后: 三写索引(ingestionApi.indexVersion) -> 版本 PUBLISHED + effective_from=now
     *        -> 旧 PUBLISHED 置 EXPIRED -> 文档 parse_status=PUBLISHED
     * 门禁 2(必审条目)由 Task 8 接入, 门禁 3(冲突)由 Task 10 接入; 本任务先做状态校验
     */
    void publish(Long versionId);

    /** 整体驳回: 版本回 DRAFT + reviewResult=REJECTED + reviewComment */
    void reject(Long versionId, String comment);

    /** 过期旧版本(发布新版本时调用, 保证检索口径唯一) */
    void expireOldVersions(Long docId, Long exceptVersionId);

}
