package cn.iocoder.yudao.module.knowledge.service.conflict.impl;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.conflict.ConflictDO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.review.ReviewItemDO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.version.AiDocVersionDO;
import cn.iocoder.yudao.module.knowledge.dal.mysql.conflict.ConflictMapper;
import cn.iocoder.yudao.module.knowledge.dal.mysql.review.ReviewItemMapper;
import cn.iocoder.yudao.module.knowledge.dal.mysql.version.AiDocVersionMapper;
import cn.iocoder.yudao.module.knowledge.service.conflict.ConflictService;
import cn.iocoder.yudao.module.knowledge.service.version.AiDocVersionService;
import cn.iocoder.yudao.module.model.api.ModelApi;
import cn.iocoder.yudao.module.model.api.dto.ModelChatReqDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.module.knowledge.enums.ErrorCodeConstants.*;

/**
 * 版本冲突检测与裁决
 */
@Slf4j
@Service
public class ConflictServiceImpl implements ConflictService {

    private static final String JUDGE_SYSTEM_PROMPT = """
            你是企业客服知识库的"条款一致性审查员"。给定同一主题下"旧版本表述"与"新版本表述", 判断两者是否构成业务冲突。
            冲突定义: 同一事项(如保修时长/价格/政策口径)新旧表述语义不一致或互相矛盾(包括同主题反义, 例如旧版"碎屏免费维修" vs 新版"碎屏不免费")。
            输出必须为 JSON: {"conflict": true/false, "reason": "一句话理由"}. 不要输出其他文字。
            """;

    private static final double SIMILARITY_THRESHOLD = 0.85;

    @Resource
    private ConflictMapper conflictMapper;
    @Resource
    private ReviewItemMapper reviewItemMapper;
    @Resource
    private AiDocVersionMapper aiDocVersionMapper;
    @Resource
    private AiDocVersionService aiDocVersionService;
    @Resource
    private ModelApi modelApi;

    @Override
    public int detectConflicts(Long versionId) {
        AiDocVersionDO newVersion = aiDocVersionService.getVersion(versionId);
        AiDocVersionDO oldVersion = aiDocVersionService.getPublishedVersion(newVersion.getDocId());
        if (oldVersion == null || oldVersion.getId().equals(versionId)) {
            return 0; // 无已发布版本(首个版本)无需检测
        }
        // 清旧检测结果(幂等)
        conflictMapper.selectListByVersionId(versionId).forEach(c -> conflictMapper.deleteById(c.getId()));

        // 新版本必审条目 vs 旧版本条目(按 title 分组)
        List<ReviewItemDO> newItems = reviewItemMapper.selectListByVersionId(versionId);
        List<ReviewItemDO> oldItems = reviewItemMapper.selectListByVersionId(oldVersion.getId());
        Map<String, List<ReviewItemDO>> oldByTitle = oldItems.stream()
                .collect(Collectors.groupingBy(ReviewItemDO::getTitle));

        int count = 0;
        for (ReviewItemDO newItem : newItems) {
            if (newItem.getMustReview() == null || !newItem.getMustReview()) {
                continue; // 只检测必审条目(高风险)
            }
            for (ReviewItemDO oldItem : oldByTitle.getOrDefault(newItem.getTitle(), List.of())) {
                if (Objects.equals(oldItem.getTitle(), newItem.getTitle())
                        && similarity(oldItem.getContent(), newItem.getContent()) < SIMILARITY_THRESHOLD) {
                    // 规则粗筛命中 -> LLM 判定(判 NO_CONFLICT 也落 PENDING 由人工确认, fail-closed)
                    JudgeResult result = judge(oldItem.getContent(), newItem.getContent());
                    ConflictDO conflict = ConflictDO.builder()
                            .versionId(versionId)
                            .oldVersionId(oldVersion.getId())
                            .docId(newVersion.getDocId())
                            .itemId(newItem.getId())
                            .title(newItem.getTitle())
                            .oldContent(oldItem.getContent())
                            .newContent(newItem.getContent())
                            .ruleHit(true)
                            .llmJudgement(result.judgement())
                            .llmReason(result.reason())
                            .status("PENDING")
                            .build();
                    conflictMapper.insert(conflict);
                    count++;
                    log.info("[detectConflicts][主题 {} 规则粗筛命中, LLM 判定: {}]", newItem.getTitle(), result.judgement());
                }
            }
        }
        // 版本标记冲突状态: 有冲突=1待裁决, 无=2已裁决(无需裁决)
        AiDocVersionDO versionUpdate = new AiDocVersionDO();
        versionUpdate.setId(versionId);
        versionUpdate.setConflictStatus(count > 0 ? 1 : 2);
        aiDocVersionMapper.updateById(versionUpdate);
        return count;
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
    public void resolve(Long conflictId, String resolveType, String comment) {
        ConflictDO conflict = conflictMapper.selectById(conflictId);
        if (conflict == null) {
            throw new ServiceException(CONFLICT_NOT_EXISTS);
        }
        if (!"PENDING".equals(conflict.getStatus())) {
            throw new ServiceException(CONFLICT_STATUS_ERROR);
        }
        ConflictDO update = new ConflictDO();
        update.setId(conflictId);
        update.setStatus(resolveType);
        update.setResolver(SecurityFrameworkUtils.getLoginUserNickname());
        update.setResolveTime(LocalDateTime.now());
        conflictMapper.updateById(update);
        // RESOLVED_OLD: 以旧版为准 -> 驳回新版本关联条目
        if ("RESOLVED_OLD".equals(resolveType) && conflict.getItemId() != null) {
            ReviewItemDO itemUpdate = new ReviewItemDO();
            itemUpdate.setId(conflict.getItemId());
            itemUpdate.setStatus("REJECTED");
            itemUpdate.setRejectReason("冲突裁决以旧版本为准: " + comment);
            reviewItemMapper.updateById(itemUpdate);
        }
        log.info("[resolve][冲突 {} 裁决为 {}]", conflictId, resolveType);
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

    /** LLM 判定结果(判定 + 理由) */
    private record JudgeResult(String judgement, String reason) {
    }

    /** LLM 判定; 失败降级: 返回 CONFLICT 转人工 */
    private JudgeResult judge(String oldContent, String newContent) {
        try {
            ModelChatReqDTO req = new ModelChatReqDTO();
            req.setSystem(JUDGE_SYSTEM_PROMPT);
            req.setUser("旧版本表述:\n" + oldContent + "\n\n新版本表述:\n" + newContent);
            String resp = modelApi.chat(req).getCheckedData();
            int start = resp.indexOf('{');
            int end = resp.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return new JudgeResult("CONFLICT", "LLM 输出无法解析, 按冲突转人工");
            }
            JSONObject json = JSONUtil.parseObj(resp.substring(start, end + 1));
            boolean conflict = Boolean.TRUE.equals(json.getBool("conflict"));
            return new JudgeResult(conflict ? "CONFLICT" : "NO_CONFLICT", json.getStr("reason"));
        } catch (Exception e) {
            log.warn("[judge][LLM 判定失败, 降级为冲突转人工: {}]", e.getMessage());
            return new JudgeResult("CONFLICT", "LLM 判定失败, 按冲突转人工");
        }
    }

}
