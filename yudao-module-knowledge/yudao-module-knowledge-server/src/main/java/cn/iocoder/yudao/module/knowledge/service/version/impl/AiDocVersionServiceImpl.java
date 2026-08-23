package cn.iocoder.yudao.module.knowledge.service.version.impl;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.eval.api.EvalApi;
import cn.iocoder.yudao.module.ingestion.api.IngestionApi;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.AiDocumentDO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.AiKnowledgeBaseDO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.version.AiDocVersionDO;
import cn.iocoder.yudao.module.knowledge.dal.mysql.review.ReviewItemMapper;
import cn.iocoder.yudao.module.knowledge.dal.mysql.version.AiDocVersionMapper;
import cn.iocoder.yudao.module.knowledge.enums.version.VersionStatusEnum;
import cn.iocoder.yudao.module.knowledge.dal.mysql.knowledge.AiDocumentMapper;
import cn.iocoder.yudao.module.knowledge.dal.mysql.knowledge.AiKnowledgeBaseMapper;
import cn.iocoder.yudao.module.knowledge.service.conflict.ConflictService;
import cn.iocoder.yudao.module.knowledge.service.intent.IntentSummarizer;
import cn.iocoder.yudao.module.knowledge.service.knowledge.KnowledgePermissionHelper;
import cn.iocoder.yudao.module.knowledge.service.slot.SlotSummarizer;
import cn.iocoder.yudao.module.knowledge.service.version.AiDocVersionService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mzt.logapi.context.LogRecordContext;
import com.mzt.logapi.starter.annotation.LogRecord;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;

import static cn.iocoder.yudao.module.knowledge.enums.ErrorCodeConstants.*;
import static cn.iocoder.yudao.module.knowledge.enums.KnowledgeLogRecordConstants.*;

/**
 * 文档版本状态机
 */
@Slf4j
@Service
public class AiDocVersionServiceImpl implements AiDocVersionService {

    @Resource
    private AiDocVersionMapper aiDocVersionMapper;
    @Resource
    private AiDocumentMapper aiDocumentMapper;
    @Resource
    private AiKnowledgeBaseMapper aiKnowledgeBaseMapper;
    @Resource
    private ReviewItemMapper reviewItemMapper;
    @Resource
    private IngestionApi ingestionApi;
    @Resource
    private ConflictService conflictService;
    @Resource
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;
    @Resource
    private IntentSummarizer intentSummarizer;
    @Resource
    private SlotSummarizer slotSummarizer;
    @Resource
    private KnowledgePermissionHelper knowledgePermissionHelper;
    @Resource
    private EvalApi evalApi;

    @Override
    public AiDocVersionDO createVersion(Long docId) {
        // 越权防线: 文档所属知识库不可见时禁止创建版本
        validateDocKbVisible(docId);
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
        // 越权防线: 文档所属知识库不可见时禁止查看版本列表
        validateDocKbVisible(docId);
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
    // C5 两阶段发布: 不再整体 @Transactional(事务内禁远程 ES/Milvus);
    // 校验门禁 → 事务外索引(失败不发布, 旧版本继续服务) → 短事务状态流转
    @LogRecord(type = PUBLISH_TYPE, subType = PUBLISH_SUB_TYPE, bizNo = "{{#versionId}}",
            success = PUBLISH_SUCCESS)
    public void publish(Long versionId) {
        AiDocVersionDO version = getVersion(versionId);
        LogRecordContext.putVariable("version", version);
        // 越权防线: 版本所属知识库不可见时禁止发布
        validateVersionKbVisible(version);
        // 幂等(正常场景): 已发布则直接返回(notifyParsed 在 Kafka 重投/重试场景可能重复触发)
        // 例外修复: 版本已 PUBLISHED 但片段被重新抽取(新片段默认 REVIEW 未发布)时, 需重跑索引同步,
        //           否则新片段不进 Milvus/ES 且永远卡 REVIEW(检索不到)。indexVersion 幂等(覆盖式重写)。
        if (VersionStatusEnum.PUBLISHED.getStatus().equals(version.getStatus())) {
            AiDocumentDO doc = aiDocumentMapper.selectById(version.getDocId());
            if (doc != null) {
                // 注册操作日志模板变量(文档/知识库可能已删, 模板用安全导航)
                LogRecordContext.putVariable("doc", doc);
                LogRecordContext.putVariable("kb", aiKnowledgeBaseMapper.selectById(doc.getKbId()));
            }
            if (doc != null && hasUnpublishedChunks(versionId)) {
                log.warn("[publish][版本 {} 已发布但存在未发布片段, 重跑索引同步]", versionId);
                CommonResult<Boolean> result = ingestionApi.indexVersion(versionId, doc.getKbId(), doc.getTenantId(), doc.getId());
                if (result.isError()) {
                    throw new ServiceException(result.getCode(), result.getMsg());
                }
                aiDocumentMapper.updateParseStatus(doc.getId(), "PUBLISHED", null, null);
            } else {
                log.info("[publish][版本 {} 已发布, 幂等跳过]", versionId);
            }
            return;
        }
        // 门禁 1: 状态必须 DRAFT(自动发布)或 REVIEW
        if (!VersionStatusEnum.REVIEW.getStatus().equals(version.getStatus())
                && !VersionStatusEnum.DRAFT.getStatus().equals(version.getStatus())) {
            throw new ServiceException(VERSION_STATUS_ERROR);
        }
        // 门禁 2: 必审条目全部处理完(无 PENDING/REJECTED; 一人审核制, 无双人复核)
        if (reviewItemMapper.existsUnfinishedRequired(versionId)) {
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
        AiDocumentDO doc = aiDocumentMapper.selectById(version.getDocId());
        if (doc == null) {
            throw new ServiceException(DOCUMENT_NOT_EXISTS);
        }
        // 注册操作日志模板变量(知识库可能已删, 模板用安全导航)
        LogRecordContext.putVariable("doc", doc);
        LogRecordContext.putVariable("kb", aiKnowledgeBaseMapper.selectById(doc.getKbId()));
        // 门禁 3.5: 版本下必须至少有 1 个内容片段, 否则发布 = 清空线上内容(空版本上线事故)
        try {
            List<cn.iocoder.yudao.module.ingestion.api.dto.ChunkRespDTO> chunks =
                    ingestionApi.getChunksByVersion(versionId).getCheckedData();
            if (chunks == null || chunks.isEmpty()) {
                throw new ServiceException(VERSION_EMPTY_CHUNK);
            }
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("[publish][版本 {} 片段数校验 RPC 异常, 保守阻断: {}]", versionId, e.getMessage(), e);
            throw new ServiceException(VERSION_EMPTY_CHUNK);
        }
        // 门禁 4: 评测闸门(仅首次发布路径; 上方幂等分支已 return, 重索引不受影响)
        // evalApi.checkGate: 闸门配置关闭 → true 放行; 无 DONE 评测或未全题达标 → false 阻断;
        // 内部实现不抛异常; 此处兜底 RPC 级故障(网络/序列化)保守阻断, 避免未评测内容上线
        try {
            CommonResult<Boolean> gate = evalApi.checkGate(doc.getKbId());
            if (gate.isError() || !Boolean.TRUE.equals(gate.getCheckedData())) {
                log.warn("[publish][版本 {} 评测闸门未通过: code={}, msg={}]",
                        versionId, gate.getCode(), gate.getMsg());
                throw new ServiceException(VERSION_GATE_BLOCKED);
            }
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("[publish][版本 {} 评测闸门 RPC 异常, 保守阻断: {}]", versionId, e.getMessage(), e);
            throw new ServiceException(VERSION_GATE_BLOCKED);
        }
        // C5 阶段2(事务外): 由 ingestion 从 MySQL embedding 写 Milvus/ES(幂等覆盖式);
        // 失败 → 不置 PUBLISHED, 旧 PUBLISHED 版本继续服务(调用方可重试)
        CommonResult<Boolean> result = ingestionApi.indexVersion(versionId, doc.getKbId(), doc.getTenantId(), doc.getId());
        if (result.isError()) {
            throw new ServiceException(result.getCode(), result.getMsg());
        }
        // C5 阶段3(短事务): 状态流转 + 旧版本过期(纯 SQL) + 文档状态
        transactionTemplate.executeWithoutResult(status -> {
            AiDocVersionDO update = new AiDocVersionDO();
            update.setId(versionId);
            update.setStatus(VersionStatusEnum.PUBLISHED.getStatus());
            update.setEffectiveFrom(LocalDateTime.now());
            update.setReviewer(currentNickname());
            aiDocVersionMapper.updateById(update);
            // 旧版本过期(仅版本状态, 索引清理在事务外)
            expireOldVersions(version.getDocId(), versionId);
            // 文档置已发布
            aiDocumentMapper.updateParseStatus(doc.getId(), "PUBLISHED", null, null);
        });
        // C5 阶段3.5(事务外): 级联清理被过期版本的检索索引(MySQL chunk 置 DISABLED + ES/Milvus 删除)
        cleanupExpiredVersionIndexes(version.getDocId(), versionId);
        // 异步 LLM 意图/槽位总结(客服类知识库专用; 专利等专业领域意图由领域固定集提供,
        // 不自动总结——否则客服式意图如"合同条款"会污染专利意图分类)
        boolean domainAutoSummarize = true;
        try {
            AiKnowledgeBaseDO kb = aiKnowledgeBaseMapper.selectById(doc.getKbId());
            if (kb != null && "PATENT".equalsIgnoreCase(kb.getDomainCode())) {
                domainAutoSummarize = false;
            }
        } catch (Exception summarizeEx) {
            log.warn("[publish][知识库 {} 领域解析失败, 按自动总结处理: {}]", doc.getKbId(), summarizeEx.getMessage());
        }
        if (domainAutoSummarize) {
            intentSummarizer.summarizeByKbAsync(doc.getKbId());
            slotSummarizer.summarizeByKbAsync(doc.getKbId());
        }
        log.info("[publish][版本 {} 发布完成, 文档 {}]", versionId, doc.getId());
    }

    /** 版本下是否存在未发布片段(经 ingestion RPC; 失败保守视为无, 不阻塞幂等跳过) */
    private boolean hasUnpublishedChunks(Long versionId) {
        try {
            return Boolean.TRUE.equals(ingestionApi.hasUnpublishedChunks(versionId).getCheckedData());
        } catch (Exception e) {
            log.warn("[publish][查询版本 {} 未发布片段失败, 按无处理: {}]", versionId, e.getMessage());
            return false;
        }
    }

    private String currentNickname() {        try {
            return SecurityFrameworkUtils.getLoginUserNickname();
        } catch (Exception e) {
            return null; // 自动发布(无登录上下文)时允许为空
        }
    }

    @Override
    @LogRecord(type = PUBLISH_TYPE, subType = REJECT_VERSION_SUB_TYPE, bizNo = "{{#versionId}}",
            success = REJECT_VERSION_SUCCESS)
    public void reject(Long versionId, String comment) {
        AiDocVersionDO version = getVersion(versionId);
        LogRecordContext.putVariable("version", version);
        // 越权防线: 版本所属知识库不可见时禁止驳回
        validateVersionKbVisible(version);
        // 仅审核中可整体驳回, 避免已发布版本被误操作回退
        if (!VersionStatusEnum.REVIEW.getStatus().equals(version.getStatus())) {
            throw new ServiceException(VERSION_STATUS_ERROR);
        }
        // 注册操作日志模板变量(文档可能已删, 模板用安全导航)
        AiDocumentDO doc = aiDocumentMapper.selectById(version.getDocId());
        LogRecordContext.putVariable("doc", doc);
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
        // 单条 UPDATE 原子过期旧已发布版本, 收窄并发发布竞态窗口(纯 SQL, 可在事务内执行)
        aiDocVersionMapper.update(null, new LambdaUpdateWrapper<AiDocVersionDO>()
                .eq(AiDocVersionDO::getDocId, docId)
                .eq(AiDocVersionDO::getStatus, VersionStatusEnum.PUBLISHED.getStatus())
                .ne(AiDocVersionDO::getId, exceptVersionId)
                .set(AiDocVersionDO::getStatus, VersionStatusEnum.EXPIRED.getStatus())
                .set(AiDocVersionDO::getEffectiveTo, LocalDateTime.now()));
    }

    /**
     * 事务外级联清理被过期版本的检索索引(C5 两阶段发布: 提交事务后调用)。
     * 旧版本 chunk 从检索层移除(MySQL 置 DISABLED + ES/Milvus 删除), 失败仅告警不阻断。
     */
    private void cleanupExpiredVersionIndexes(Long docId, Long exceptVersionId) {
        List<AiDocVersionDO> expiredVersions = aiDocVersionMapper.selectList(
                new LambdaQueryWrapper<AiDocVersionDO>()
                        .eq(AiDocVersionDO::getDocId, docId)
                        .eq(AiDocVersionDO::getStatus, VersionStatusEnum.EXPIRED.getStatus())
                        .ne(AiDocVersionDO::getId, exceptVersionId)
                        .orderByDesc(AiDocVersionDO::getUpdateTime));
        for (AiDocVersionDO v : expiredVersions) {
            try {
                CommonResult<Boolean> result = ingestionApi.deleteVersionIndex(v.getId());
                if (result.isError()) {
                    log.error("[cleanupExpiredVersionIndexes][版本 {} 索引清理失败: {}]", v.getId(), result.getMsg());
                }
            } catch (Exception e) {
                log.error("[cleanupExpiredVersionIndexes][版本 {} 索引清理异常: {}]", v.getId(), e.getMessage());
            }
        }
    }

    /**
     * 校验文档所属知识库对当前用户可见(越权 0 容忍; 内部调用/RPC 无登录态直通)。
     * 知识库已删 → 文档校验为不可见(保守)。
     */
    private void validateDocKbVisible(Long docId) {
        AiDocumentDO doc = aiDocumentMapper.selectById(docId);
        if (doc == null) {
            throw new ServiceException(DOCUMENT_NOT_EXISTS);
        }
        Long userId = cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId();
        if (userId == null || knowledgePermissionHelper.isSuperAdmin(userId)) {
            return; // 无登录态(内部调用/RPC)或超管直通
        }
        AiKnowledgeBaseDO kb = aiKnowledgeBaseMapper.selectById(doc.getKbId());
        if (kb == null || !knowledgePermissionHelper.isKbVisibleToUser(userId, kb)) {
            throw new ServiceException(KB_NOT_VISIBLE);
        }
    }

    /** 校验版本所属文档的知识库可见性(版本操作统一入口) */
    private void validateVersionKbVisible(AiDocVersionDO version) {
        validateDocKbVisible(version.getDocId());
    }

}
