package cn.iocoder.yudao.module.knowledge.service.version.impl;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.ingestion.api.IngestionApi;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.AiDocumentDO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.version.AiDocVersionDO;
import cn.iocoder.yudao.module.knowledge.dal.mysql.review.ReviewItemMapper;
import cn.iocoder.yudao.module.knowledge.dal.mysql.version.AiDocVersionMapper;
import cn.iocoder.yudao.module.knowledge.enums.version.VersionStatusEnum;
import cn.iocoder.yudao.module.knowledge.service.knowledge.AiDocumentService;
import cn.iocoder.yudao.module.knowledge.service.conflict.ConflictService;
import cn.iocoder.yudao.module.knowledge.service.version.AiDocVersionService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static cn.iocoder.yudao.module.knowledge.enums.ErrorCodeConstants.*;

/**
 * 文档版本状态机
 */
@Slf4j
@Service
public class AiDocVersionServiceImpl implements AiDocVersionService {

    @Resource
    private AiDocVersionMapper aiDocVersionMapper;
    @Resource
    private AiDocumentService aiDocumentService;
    @Resource
    private ReviewItemMapper reviewItemMapper;
    @Resource
    private IngestionApi ingestionApi;
    @Resource
    private ConflictService conflictService;

    @Override
    public AiDocVersionDO createVersion(Long docId) {
        AiDocVersionDO latest = aiDocVersionMapper.selectLatestByDocId(docId);
        int next = (latest == null ? 0 : parseVersionNo(latest.getVersionNo())) + 1;
        AiDocVersionDO version = AiDocVersionDO.builder()
                .docId(docId)
                .versionNo("V" + next)
                .status(VersionStatusEnum.DRAFT.getStatus())
                .conflictStatus(0)
                .build();
        aiDocVersionMapper.insert(version);
        log.info("[createVersion][文档 {} 创建版本 {}]", docId, version.getVersionNo());
        return version;
    }

    private int parseVersionNo(String versionNo) {
        try {
            return Integer.parseInt(StrUtil.removePrefix(versionNo, "V"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public AiDocVersionDO getVersion(Long id) {
        AiDocVersionDO version = aiDocVersionMapper.selectById(id);
        if (version == null) {
            throw new ServiceException(VERSION_NOT_EXISTS);
        }
        return version;
    }

    @Override
    public AiDocVersionDO getLatestVersion(Long docId) {
        return aiDocVersionMapper.selectLatestByDocId(docId);
    }

    @Override
    public AiDocVersionDO getPublishedVersion(Long docId) {
        return aiDocVersionMapper.selectPublishedByDocId(docId);
    }

    @Override
    public List<AiDocVersionDO> getVersionList(Long docId) {
        return aiDocVersionMapper.selectListByDocId(docId);
    }

    @Override
    public List<AiDocVersionDO> getVersionListByIds(java.util.Collection<Long> versionIds) {
        if (versionIds == null || versionIds.isEmpty()) {
            return List.of();
        }
        return aiDocVersionMapper.selectBatchIds(versionIds);
    }

    @Override
    public void submitForReview(Long versionId) {
        AiDocVersionDO version = getVersion(versionId);
        if (!VersionStatusEnum.DRAFT.getStatus().equals(version.getStatus())
                && !VersionStatusEnum.REVIEW.getStatus().equals(version.getStatus())) {
            throw new ServiceException(VERSION_STATUS_ERROR);
        }
        AiDocVersionDO update = new AiDocVersionDO();
        update.setId(versionId);
        update.setStatus(VersionStatusEnum.REVIEW.getStatus());
        aiDocVersionMapper.updateById(update);
    }

    @Override
    @Transactional
    public void publish(Long versionId) {
        AiDocVersionDO version = getVersion(versionId);
        // 幂等: 已发布则直接返回(notifyParsed 在 Kafka 重投/重试场景可能重复触发)
        if (VersionStatusEnum.PUBLISHED.getStatus().equals(version.getStatus())) {
            log.info("[publish][版本 {} 已发布, 幂等跳过]", versionId);
            return;
        }
        // 门禁 1: 状态必须 DRAFT(自动发布)或 REVIEW
        if (!VersionStatusEnum.REVIEW.getStatus().equals(version.getStatus())
                && !VersionStatusEnum.DRAFT.getStatus().equals(version.getStatus())) {
            throw new ServiceException(VERSION_STATUS_ERROR);
        }
        // 门禁 2: 必审条目全部处理完(无 PENDING/REJECTED; 价格类双人复核完成)
        if (reviewItemMapper.existsUnfinishedRequired(versionId)
                || reviewItemMapper.existsPriceWithoutDoubleReview(versionId)) {
            throw new ServiceException(VERSION_PUBLISH_BLOCKED);
        }
        // 门禁 3: 无待裁决冲突(先查存量, 再增量检测, 再复查)
        // detectConflicts 内部以 REQUIRES_NEW 独立事务持久 PENDING 冲突记录, 不受本发布事务回滚影响
        if (conflictService.hasPendingConflicts(versionId)) {
            throw new ServiceException(CONFLICT_PENDING_EXISTS);
        }
        conflictService.detectConflicts(versionId);
        if (conflictService.hasPendingConflicts(versionId)) {
            throw new ServiceException(CONFLICT_PENDING_EXISTS);
        }
        AiDocumentDO doc = aiDocumentService.getAiDocument(version.getDocId());
        if (doc == null) {
            throw new ServiceException(DOCUMENT_NOT_EXISTS);
        }
        // 三写: 由 ingestion 从 MySQL embedding 写 Milvus/ES(Task 4 填充实现)
        CommonResult<Boolean> result = ingestionApi.indexVersion(versionId, doc.getKbId(), doc.getTenantId());
        if (result.isError()) {
            throw new ServiceException(result.getCode(), result.getMsg());
        }
        // 状态流转
        AiDocVersionDO update = new AiDocVersionDO();
        update.setId(versionId);
        update.setStatus(VersionStatusEnum.PUBLISHED.getStatus());
        update.setEffectiveFrom(LocalDateTime.now());
        update.setReviewer(currentNickname());
        aiDocVersionMapper.updateById(update);
        // 旧版本过期
        expireOldVersions(version.getDocId(), versionId);
        // 文档置已发布
        aiDocumentService.updateParseStatus(doc.getId(), "PUBLISHED", null, null);
        log.info("[publish][版本 {} 发布完成, 文档 {}]", versionId, doc.getId());
    }

    private String currentNickname() {
        try {
            return SecurityFrameworkUtils.getLoginUserNickname();
        } catch (Exception e) {
            return null; // 自动发布(无登录上下文)时允许为空
        }
    }

    @Override
    public void reject(Long versionId, String comment) {
        AiDocVersionDO version = getVersion(versionId);
        // 仅审核中可整体驳回, 避免已发布版本被误操作回退
        if (!VersionStatusEnum.REVIEW.getStatus().equals(version.getStatus())) {
            throw new ServiceException(VERSION_STATUS_ERROR);
        }
        AiDocVersionDO update = new AiDocVersionDO();
        update.setId(versionId);
        update.setStatus(VersionStatusEnum.DRAFT.getStatus());
        update.setReviewResult("REJECTED");
        update.setReviewComment(comment);
        aiDocVersionMapper.updateById(update);
        log.info("[reject][版本 {} 驳回, 原因: {}]", versionId, comment);
    }

    @Override
    public void expireOldVersions(Long docId, Long exceptVersionId) {
        // 单条 UPDATE 原子过期旧已发布版本, 收窄并发发布竞态窗口
        aiDocVersionMapper.update(null, new LambdaUpdateWrapper<AiDocVersionDO>()
                .eq(AiDocVersionDO::getDocId, docId)
                .eq(AiDocVersionDO::getStatus, VersionStatusEnum.PUBLISHED.getStatus())
                .ne(AiDocVersionDO::getId, exceptVersionId)
                .set(AiDocVersionDO::getStatus, VersionStatusEnum.EXPIRED.getStatus())
                .set(AiDocVersionDO::getEffectiveTo, LocalDateTime.now()));
    }

}
