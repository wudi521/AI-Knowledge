package cn.iocoder.yudao.module.chat.service.context;

import cn.iocoder.yudao.module.chat.dal.dataobject.context.AiChatContextFrameDO;
import cn.iocoder.yudao.module.chat.dal.dataobject.context.AiChatResultSetDO;
import cn.iocoder.yudao.module.chat.dal.mysql.context.AiChatContextFrameMapper;
import cn.iocoder.yudao.module.chat.dal.mysql.context.AiChatResultSetMapper;
import cn.iocoder.yudao.module.chat.framework.chat.ChatProperties;
import cn.iocoder.yudao.module.chat.service.context.model.ContextFrame;
import cn.iocoder.yudao.module.chat.service.context.model.ConversationQueryState;
import cn.iocoder.yudao.module.chat.service.context.model.ResultSetSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    /** 物化结果集实体 id: INLINE 直接返回; REF 暂返回空(需按 scope 描述重建, 见 CQ-26/步骤8) */
    public List<Long> materialize(ResultSetSnapshot snapshot) {
        if (snapshot == null) {
            return Collections.emptyList();
        }
        if (ResultSetSnapshot.STORAGE_REF.equals(snapshot.getStorageMode()) || snapshot.getOrderedEntityIds() == null) {
            return Collections.emptyList();
        }
        return snapshot.getOrderedEntityIds();
    }

}
