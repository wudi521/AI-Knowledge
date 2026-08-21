package cn.iocoder.yudao.module.knowledge.service.conflict.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.conflict.ConflictDO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.AiDocumentDO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.AiKnowledgeBaseDO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.review.ReviewItemDO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.version.AiDocVersionDO;
import cn.iocoder.yudao.module.knowledge.dal.mysql.conflict.ConflictMapper;
import cn.iocoder.yudao.module.knowledge.dal.mysql.knowledge.AiDocumentMapper;
import cn.iocoder.yudao.module.knowledge.dal.mysql.knowledge.AiKnowledgeBaseMapper;
import cn.iocoder.yudao.module.knowledge.dal.mysql.review.ReviewItemMapper;
import cn.iocoder.yudao.module.knowledge.dal.mysql.version.AiDocVersionMapper;
import cn.iocoder.yudao.module.knowledge.enums.conflict.ConflictStatusEnum;
import cn.iocoder.yudao.module.knowledge.enums.review.ReviewItemStatusEnum;
import cn.iocoder.yudao.module.knowledge.service.conflict.ConflictService;
import cn.iocoder.yudao.module.knowledge.service.knowledge.KnowledgePermissionHelper;
import cn.iocoder.yudao.module.knowledge.service.prompt.PromptSupport;
import cn.iocoder.yudao.module.knowledge.service.version.AiDocVersionService;
import cn.iocoder.yudao.module.model.api.ModelApi;
import cn.iocoder.yudao.module.model.api.dto.ModelChatReqDTO;
import com.mzt.logapi.context.LogRecordContext;
import com.mzt.logapi.starter.annotation.LogRecord;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.module.knowledge.enums.ErrorCodeConstants.CONFLICT_NOT_EXISTS;
import static cn.iocoder.yudao.module.knowledge.enums.ErrorCodeConstants.CONFLICT_STATUS_ERROR;
import static cn.iocoder.yudao.module.knowledge.enums.ErrorCodeConstants.KB_NOT_VISIBLE;
import static cn.iocoder.yudao.module.knowledge.enums.KnowledgeLogRecordConstants.*;

/**
 * 版本冲突检测与裁决
 * <p>
 * 流程: 规则粗筛(同主题 + 文本差异大) -> LLM 判定 -> 落库 PENDING -> 人工裁决。
 * LLM 调用在事务外执行(避免长事务占连接); 仅 DB 写(清 PENDING + 插入 + 回写版本冲突状态)
 * 放在 REQUIRES_NEW 独立事务, 保证发布事务回滚时冲突记录仍持久可裁决。
 */
@Slf4j
@Service
public class ConflictServiceImpl implements ConflictService {

    private static final String JUDGE_SYSTEM_PROMPT = """
            你是企业客服知识库的"条款一致性审查员"。给定同一主题下"旧版本表述"与"新版本表述", 判断两者是否构成业务冲突。
            冲突定义: 同一事项(如保修时长/价格/政策口径)新旧表述语义不一致或互相矛盾(包括同主题反义, 例如旧版"碎屏免费维修" vs 新版"碎屏不免费")。
            输出必须为 JSON: {"conflict": true/false, "reason": "一句话理由"}. 不要输出其他文字。
            """;

    /** 文本相似度低于该值视为"表述不一致"(候选冲突) */
    private static final double SIMILARITY_THRESHOLD = 0.85;

    /** 单次检测最多提交给 LLM 判定的候选对数(防发布链路被拖垮) */
    private static final int MAX_JUDGE_PAIRS = 10;

    /** llm_reason 列 varchar(1000) */
    private static final int LLM_REASON_MAX_LEN = 1000;

    /** reject_reason 列 varchar(512) */
    private static final int REJECT_REASON_MAX_LEN = 500;

    @Resource
    private ConflictMapper conflictMapper;
    @Resource
    private ReviewItemMapper reviewItemMapper;
    @Resource
    private AiDocVersionMapper aiDocVersionMapper;
    @Resource
    private AiDocVersionService aiDocVersionService;
    @Resource
    private AiDocumentMapper aiDocumentMapper;
    @Resource
    private AiKnowledgeBaseMapper aiKnowledgeBaseMapper;
    @Resource
    private KnowledgePermissionHelper knowledgePermissionHelper;
    @Resource
    private ModelApi modelApi;
    @Resource
    private PromptSupport promptSupport;
    @Resource
    private PlatformTransactionManager transactionManager;

    @Override
    public int detectConflicts(Long versionId) {
        AiDocVersionDO newVersion = aiDocVersionService.getVersion(versionId);
        AiDocVersionDO oldVersion = aiDocVersionService.getPublishedVersion(newVersion.getDocId());
        if (oldVersion == null || oldVersion.getId().equals(versionId)) {
            // 无已发布版本: 不可能有冲突, 版本冲突状态直接标记"已裁决(无冲突)"
            updateConflictStatus(versionId, 2);
            return 0;
        }
        // 已裁决记录: 同 (versionId, itemId) 已有非 PENDING 裁决则跳过,
        // 避免"裁决以新版为准 -> 重新检测重建 PENDING -> 再裁决"的死循环, 同时保留裁决审计历史
        Set<Long> resolvedItemIds = conflictMapper.selectListByVersionId(versionId).stream()
                .filter(c -> !ConflictStatusEnum.PENDING.getStatus().equals(c.getStatus()))
                .map(ConflictDO::getItemId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());

        // 1. 内存计算候选对(不持事务/连接)
        List<ReviewItemDO> newItems = reviewItemMapper.selectListByVersionId(versionId);
        List<ReviewItemDO> oldItems = reviewItemMapper.selectListByVersionId(oldVersion.getId());
        Map<String, List<ReviewItemDO>> oldByTitle = oldItems.stream()
                .collect(Collectors.groupingBy(ReviewItemDO::getTitle));
        List<Pair> candidates = new ArrayList<>();
        for (ReviewItemDO newItem : newItems) {
            if (!Boolean.TRUE.equals(newItem.getMustReview()) || resolvedItemIds.contains(newItem.getId())) {
                continue; // 只检测必审条目; 已裁决的跳过
            }
            for (ReviewItemDO oldItem : oldByTitle.getOrDefault(newItem.getTitle(), List.of())) {
                if (similarity(oldItem.getContent(), newItem.getContent()) < SIMILARITY_THRESHOLD) {
                    candidates.add(new Pair(newItem, oldItem));
                }
            }
        }
        if (candidates.size() > MAX_JUDGE_PAIRS) {
            log.warn("[detectConflicts][版本 {} 候选 {} 对, 超过上限 {} 截断, 未覆盖的转人工处理]",
                    versionId, candidates.size(), MAX_JUDGE_PAIRS);
            candidates = new ArrayList<>(candidates.subList(0, MAX_JUDGE_PAIRS));
        }

        // 2. LLM 判定(事务外, 避免长事务占连接)
        List<ConflictDO> conflicts = new ArrayList<>();
        for (Pair p : candidates) {
            JudgeResult r = judge(p.oldItem().getContent(), p.newItem().getContent());
            conflicts.add(ConflictDO.builder()
                    .versionId(versionId)
                    .oldVersionId(oldVersion.getId())
                    .docId(newVersion.getDocId())
                    .itemId(p.newItem().getId())
                    .title(p.newItem().getTitle())
                    .oldContent(p.oldItem().getContent())
                    .newContent(p.newItem().getContent())
                    .ruleHit(true)
                    .llmJudgement(r.judgement())
                    .llmReason(StrUtil.sub(r.reason(), 0, LLM_REASON_MAX_LEN))
                    .status(ConflictStatusEnum.PENDING.getStatus())
                    .build());
        }

        // 3. REQUIRES_NEW 独立事务落库(发布回滚不影响; 只清 PENDING, 保留裁决历史)
        final int count = conflicts.size();
        TransactionTemplate conflictTx = new TransactionTemplate(transactionManager);
        conflictTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        conflictTx.execute(status -> {
            conflictMapper.deletePendingByVersionId(versionId);
            if (!conflicts.isEmpty()) {
                conflictMapper.insertBatch(conflicts);
            }
            updateConflictStatus(versionId, count > 0 ? 1 : 2);
            return null;
        });
        log.info("[detectConflicts][版本 {} 检测出冲突 {} 条]", versionId, count);
        return count;
    }

    private void updateConflictStatus(Long versionId, int conflictStatus) {
        AiDocVersionDO versionUpdate = new AiDocVersionDO();
        versionUpdate.setId(versionId);
        versionUpdate.setConflictStatus(conflictStatus);
        aiDocVersionMapper.updateById(versionUpdate);
    }

    @Override
    public boolean hasPendingConflicts(Long versionId) {
        return conflictMapper.existsPendingByVersionId(versionId);
    }

    @Override
    public List<ConflictDO> getConflictList(Long docId, String status) {
        return conflictMapper.selectListByDocIdAndStatus(docId, status);
    }

    @Override
    @Transactional // 冲突状态更新与关联条目驳回必须原子, 防止"冲突已解决但条目未驳回"后发布
    @LogRecord(type = CONFLICT_TYPE, subType = CONFLICT_RESOLVE_SUB_TYPE, bizNo = "{{#conflictId}}",
            success = CONFLICT_RESOLVE_SUCCESS)
    public void resolve(Long conflictId, String resolveType, String comment) {
        ConflictStatusEnum type = ConflictStatusEnum.fromStatus(resolveType);
        if (type == null || ConflictStatusEnum.PENDING.equals(type)) {
            throw new ServiceException(CONFLICT_STATUS_ERROR); // 非法裁决类型
        }
        ConflictDO conflict = conflictMapper.selectById(conflictId);
        if (conflict == null) {
            throw new ServiceException(CONFLICT_NOT_EXISTS);
        }
        // 越权防线: 冲突所属文档的知识库不可见时禁止裁决
        validateConflictKbVisible(conflict);
        if (!ConflictStatusEnum.PENDING.getStatus().equals(conflict.getStatus())) {
            throw new ServiceException(CONFLICT_STATUS_ERROR);
        }
        // 注册操作日志模板变量(裁决结果用枚举中文名, 如"已裁决·以新版为准")
        LogRecordContext.putVariable("conflict", conflict);
        LogRecordContext.putVariable("resolveResult", type.getName());
        ConflictDO update = new ConflictDO();
        update.setId(conflictId);
        update.setStatus(type.getStatus());
        update.setResolver(SecurityFrameworkUtils.getLoginUserNickname());
        update.setResolveTime(LocalDateTime.now());
        conflictMapper.updateById(update);
        // RESOLVED_OLD: 以旧版为准 -> 驳回新版本关联条目
        if (ConflictStatusEnum.RESOLVED_OLD.equals(type) && conflict.getItemId() != null) {
            ReviewItemDO itemUpdate = new ReviewItemDO();
            itemUpdate.setId(conflict.getItemId());
            itemUpdate.setStatus(ReviewItemStatusEnum.REJECTED.getStatus());
            itemUpdate.setRejectReason(StrUtil.sub("冲突裁决以旧版本为准: " + StrUtil.nullToEmpty(comment), 0, REJECT_REASON_MAX_LEN));
            reviewItemMapper.updateById(itemUpdate);
        }
        log.info("[resolve][冲突 {} 裁决为 {}]", conflictId, type.getStatus());
    }

    /** 字符级 Jaccard 相似度 */
    private double similarity(String a, String b) {
        if (a == null || b == null) {
            return 0;
        }
        Set<Character> setA = new HashSet<>();
        for (char c : a.toCharArray()) {
            setA.add(c);
        }
        Set<Character> setB = new HashSet<>();
        for (char c : b.toCharArray()) {
            setB.add(c);
        }
        Set<Character> inter = new HashSet<>(setA);
        inter.retainAll(setB);
        Set<Character> union = new HashSet<>(setA);
        union.addAll(setB);
        return union.isEmpty() ? 0 : (double) inter.size() / union.size();
    }

    /** LLM 判定; 失败/输出异常降级为 CONFLICT 转人工(fail-closed) */
    private JudgeResult judge(String oldContent, String newContent) {
        try {
            ModelChatReqDTO req = new ModelChatReqDTO();
            req.setSystem(promptSupport.get("conflict-rule", JUDGE_SYSTEM_PROMPT));
            req.setUser("旧版本表述:\n" + oldContent + "\n\n新版本表述:\n" + newContent);
            String resp = modelApi.chat(req).getCheckedData();
            int start = resp.indexOf('{');
            int end = resp.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return new JudgeResult("CONFLICT", "LLM 输出无法解析, 按冲突转人工");
            }
            JSONObject json = JSONUtil.parseObj(resp.substring(start, end + 1));
            Boolean conflict = json.getBool("conflict");
            // 缺少 conflict 键视为异常输出 -> fail-closed
            return conflict == null
                    ? new JudgeResult("CONFLICT", "LLM 输出缺少 conflict 键, 按冲突转人工")
                    : new JudgeResult(conflict ? "CONFLICT" : "NO_CONFLICT", json.getStr("reason", ""));
        } catch (Exception e) {
            log.warn("[judge][LLM 判定失败, 降级为冲突转人工: {}]", e.getMessage());
            return new JudgeResult("CONFLICT", "LLM 判定异常: " + StrUtil.sub(e.getMessage(), 0, 200));
        }
    }

    /** 候选对(新条目 vs 旧条目) */
    private record Pair(ReviewItemDO newItem, ReviewItemDO oldItem) {
    }

    /** LLM 判定结果 */
    private record JudgeResult(String judgement, String reason) {
    }

    /** 越权防线: 冲突所属文档的知识库不可见时禁止裁决(超管/无登录态直通) */
    private void validateConflictKbVisible(ConflictDO conflict) {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        if (userId == null || knowledgePermissionHelper.isSuperAdmin(userId)) {
            return;
        }
        AiDocumentDO doc = conflict.getDocId() == null ? null : aiDocumentMapper.selectById(conflict.getDocId());
        Long kbId = doc == null ? null : doc.getKbId();
        if (kbId == null) {
            throw new ServiceException(KB_NOT_VISIBLE);
        }
        AiKnowledgeBaseDO kb = aiKnowledgeBaseMapper.selectById(kbId);
        if (kb == null || !knowledgePermissionHelper.isKbVisibleToUser(userId, kb)) {
            throw new ServiceException(KB_NOT_VISIBLE);
        }
    }

}
