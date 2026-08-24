package cn.iocoder.yudao.module.chat.service.context;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.chat.dal.dataobject.context.AiChatContextFrameDO;
import cn.iocoder.yudao.module.chat.dal.dataobject.context.AiChatResultSetDO;
import cn.iocoder.yudao.module.chat.dal.mysql.context.AiChatContextFrameMapper;
import cn.iocoder.yudao.module.chat.dal.mysql.context.AiChatResultSetMapper;
import cn.iocoder.yudao.module.chat.framework.chat.ChatProperties;
import cn.iocoder.yudao.module.chat.service.context.model.ContextFrame;
import cn.iocoder.yudao.module.chat.service.context.model.ConversationQueryState;
import cn.iocoder.yudao.module.chat.service.context.model.ResultSetSnapshot;
import cn.iocoder.yudao.module.chat.service.context.model.RevalidationResult;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import cn.iocoder.yudao.module.knowledge.api.dto.DocumentVisibilityReqDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryReqDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryRespDTO;
import cn.iocoder.yudao.module.knowledge.api.dto.StructuredQueryRowDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 多轮查询结果集服务(ResultSetSnapshot + ContextFrame 栈, CQ-02/03/22/26/34)
 * <p>
 * - 小结果集 INLINE 存保序实体 id; 大结果集 REF 仅存 scope 描述 + 知识修订标记, 按需 materialize。
 * - 每轮 query 成功后 push 一帧到上下文栈(保留最近 N), 并更新会话轻量查询状态。
 */
@Slf4j
@Service
public class ResultSetService {

    @Resource
    private AiChatResultSetMapper resultSetMapper;
    @Resource
    private AiChatContextFrameMapper frameMapper;
    @Resource
    private ChatProperties chatProperties;
    @Resource
    private ConversationQueryStateService queryStateService;
    @Resource
    private KnowledgeApi knowledgeApi;

    /** 创建/持久化结果集快照(按阈值决定 INLINE/REF), 返回落库后的快照 */
    public ResultSetSnapshot createResultSet(ResultSetSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        int threshold = chatProperties.getResultSetInlineThreshold();
        int count = snapshot.getEntityCount() != null ? snapshot.getEntityCount()
                : (snapshot.getOrderedEntityIds() != null ? snapshot.getOrderedEntityIds().size() : 0);
        snapshot.setEntityCount(count);
        if (snapshot.getOrderedEntityIds() != null && count > threshold) {
            // 大结果集: 改 REF, 丢弃 ids, 保留 scope 描述按需重建(CQ-26)
            snapshot.setStorageMode(ResultSetSnapshot.STORAGE_REF);
            snapshot.setOrderedEntityIds(null);
        } else {
            snapshot.setStorageMode(ResultSetSnapshot.STORAGE_INLINE);
        }
        if (snapshot.getStatus() == null) {
            snapshot.setStatus(ResultSetSnapshot.STATUS_VALID);
        }
        resultSetMapper.insert(snapshot.toDO());
        return snapshot;
    }

    /** 按结果集编号读取快照 */
    public ResultSetSnapshot getResultSet(String resultSetId) {
        if (resultSetId == null) {
            return null;
        }
        return ResultSetSnapshot.fromDO(resultSetMapper.selectByResultSetId(resultSetId));
    }

    /** CQ-02/47 幂等: 该 queryId 是否已有结果集快照(不依赖帧窗口, SSE 重试/重复提交去重) */
    public boolean existsByQueryId(String queryId) {
        if (queryId == null) {
            return false;
        }
        return resultSetMapper.existsByQueryId(queryId);
    }

    /** 标记结果集过期(知识/权限变化后由 revalidate 触发) */
    public void markStale(String resultSetId) {
        if (resultSetId == null) {
            return;
        }
        AiChatResultSetDO row = resultSetMapper.selectByResultSetId(resultSetId);
        if (row != null && !ResultSetSnapshot.STATUS_STALE.equals(row.getStatus())) {
            row.setStatus(ResultSetSnapshot.STATUS_STALE);
            resultSetMapper.updateById(row);
        }
    }

    /**
     * CQ-38: 结果集引用重校验(多轮引用前执行)。
     * <p>
     * 校验: 结果集存在 → tenant/kb/domain 与当前上下文一致 → 文档存在 + DOCUMENT ACL 可见(继承 KB ACL)
     * + 发布版本有效。失效处理: 全部失效 → invalid(reasonCode); 部分失效 → 剔除不可见, contextChanged=true,
     * 引用方用 remainingIds; 全部有效 → valid。
     */
    public RevalidationResult revalidate(String resultSetId, Long userId, Long kbId, String domainCode) {
        ResultSetSnapshot rs = getResultSet(resultSetId);
        if (rs == null) {
            return RevalidationResult.invalid("STALE_RESULT_SET");
        }
        if (kbId != null && rs.getKbId() != null && !kbId.equals(rs.getKbId())) {
            return RevalidationResult.invalid("DOMAIN_MISMATCH");
        }
        if (StrUtil.isNotBlank(domainCode) && StrUtil.isNotBlank(rs.getDomainCode())
                && !domainCode.equals(rs.getDomainCode())) {
            return RevalidationResult.invalid("DOMAIN_MISMATCH");
        }
        // REF 大结果集/空集: 无逐实体校验(REF materialize 见 CQ-26/阶段4), 仅 kb/domain 一致即 valid
        if (ResultSetSnapshot.STORAGE_REF.equals(rs.getStorageMode()) || CollUtil.isEmpty(rs.getOrderedEntityIds())) {
            return RevalidationResult.valid();
        }
        if (userId == null) {
            // 无用户上下文(内部调用)跳过 ACL/版本逐实体校验
            return RevalidationResult.valid();
        }
        Map<Long, String> visibility;
        try {
            DocumentVisibilityReqDTO req = new DocumentVisibilityReqDTO();
            req.setDocumentIds(rs.getOrderedEntityIds());
            req.setUserId(userId);
            CommonResult<Map<Long, String>> result = knowledgeApi.getDocumentVisibility(req);
            visibility = result != null && result.isSuccess() ? result.getData() : null;
        } catch (Exception e) {
            log.warn("[revalidate][结果集({}) 可见性 RPC 失败, 保守放行: {}]", resultSetId, e.getMessage());
            return RevalidationResult.valid();
        }
        List<Long> remaining = new ArrayList<>();
        List<Long> removed = new ArrayList<>();
        if (visibility != null) {
            for (Long id : rs.getOrderedEntityIds()) {
                if ("VISIBLE".equals(visibility.get(id))) {
                    remaining.add(id);
                } else {
                    removed.add(id);
                }
            }
        }
        if (removed.isEmpty()) {
            return RevalidationResult.valid();
        }
        if (remaining.isEmpty()) {
            markStale(resultSetId);
            boolean permissionOnly = visibility != null && removed.stream()
                    .allMatch(id -> "PERMISSION_CHANGED".equals(visibility.get(id)));
            return RevalidationResult.invalid(permissionOnly ? "PERMISSION_CHANGED" : "STALE_RESULT_SET");
        }
        // 部分失效: 剔除不可见实体, 标记 contextChanged(剩余仍可用, 语义已变)
        markStale(resultSetId);
        return RevalidationResult.partial(remaining, removed);
    }

    /** 查询会话上下文帧栈(最近在前) */
    public List<ContextFrame> getRecentFrames(Long conversationId) {
        List<AiChatContextFrameDO> rows = frameMapper.selectRecentByConversationId(conversationId, 100);
        if (rows.isEmpty()) {
            return Collections.emptyList();
        }
        List<ContextFrame> frames = new ArrayList<>(rows.size());
        for (AiChatContextFrameDO row : rows) {
            frames.add(ContextFrame.fromDO(row));
        }
        return frames;
    }

    /** push 一帧到上下文栈(seq 递增), 清理超限旧帧, 并更新会话查询状态 */
    public ContextFrame pushFrame(ContextFrame frame) {
        if (frame == null || frame.getConversationId() == null) {
            return frame;
        }
        List<AiChatContextFrameDO> recent = frameMapper.selectRecentByConversationId(frame.getConversationId(), 1);
        int nextSeq = recent.isEmpty() ? 1 : recent.get(0).getSeq() + 1;
        frame.setSeq(nextSeq);
        frameMapper.insert(frame.toDO());
        int limit = chatProperties.getContextFrameLimit();
        if (limit > 0 && nextSeq > limit) {
            frameMapper.deleteOlderThan(frame.getConversationId(), nextSeq - limit + 1);
        }
        // 更新会话轻量查询状态(引用 + 计数, 不存大 ID 集)
        ResultSetSnapshot rs = frame.getResultSetId() != null ? getResultSet(frame.getResultSetId()) : null;
        ConversationQueryState state = ConversationQueryState.builder()
                .lastResultSetId(frame.getResultSetId())
                .entityType(frame.getEntityType())
                .entityCount(rs != null ? rs.getEntityCount() : null)
                .lastMetric(frame.getMetricCode())
                .lastField(frame.getFieldCode())
                .lastOperation(frame.getOperation())
                .lastQueryId(frame.getQueryId())
                .build();
        queryStateService.updateQueryState(frame.getConversationId(), state);
        return frame;
    }

    /** 物化结果集实体 id: INLINE 直接返回; REF 按 scope_descriptor 重建(CQ-26/阶段4) */
    public List<Long> materialize(ResultSetSnapshot snapshot) {
        if (snapshot == null) {
            return Collections.emptyList();
        }
        if (ResultSetSnapshot.STORAGE_INLINE.equals(snapshot.getStorageMode()) && snapshot.getOrderedEntityIds() != null) {
            return snapshot.getOrderedEntityIds();
        }
        // REF 大结果集: 按范围描述重建实体 id(基于当前已发布数据, 天然反映最新版本)
        return materializeRef(snapshot);
    }

    /** REF 结果集按 scope_descriptor 重建保序实体 id(白名单结构化查询, 非任意 SQL) */
    private List<Long> materializeRef(ResultSetSnapshot snapshot) {
        if (snapshot == null || StrUtil.isBlank(snapshot.getScopeDescriptor())) {
            return Collections.emptyList();
        }
        try {
            JSONObject scope = JSONUtil.parseObj(snapshot.getScopeDescriptor());
            Long kbId = scope.getLong("kbId");
            String metricCode = scope.getStr("metricCode");
            String fieldCode = scope.getStr("fieldCode");
            if (kbId == null || (StrUtil.isBlank(metricCode) && StrUtil.isBlank(fieldCode))) {
                return Collections.emptyList();
            }
            StructuredQueryReqDTO req = new StructuredQueryReqDTO();
            req.setKbId(kbId);
            req.setMetricCode(metricCode);
            req.setFieldCode(fieldCode);
            req.setPublishedOnly(true);
            CommonResult<StructuredQueryRespDTO> resp = knowledgeApi.structuredQuery(req);
            if (resp == null || !resp.isSuccess() || resp.getData() == null || resp.getData().getRows() == null) {
                return Collections.emptyList();
            }
            List<Long> ids = new ArrayList<>();
            for (StructuredQueryRowDTO row : resp.getData().getRows()) {
                if (row != null && row.getDocumentId() != null) {
                    ids.add(row.getDocumentId());
                }
            }
            return ids;
        } catch (Exception e) {
            log.warn("[materializeRef][结果集({}) REF 重建失败: {}]", snapshot.getResultSetId(), e.getMessage());
            return Collections.emptyList();
        }
    }

}
