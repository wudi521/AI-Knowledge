package cn.iocoder.yudao.module.chat.service.context.model;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.chat.dal.dataobject.context.AiChatResultSetDO;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * ResultSetSnapshot(多轮查询结果集快照, CQ-02/03)
 * <p>
 * 结构化/实体查询成功后形成, 保序保存实体 id; 大结果集用 REF(仅存 scope 描述 + 知识修订标记,
 * 按需 materialize)。禁止只存 answer 文本 —— 必须知道"这 4 个是谁"。
 */
@Data
@Builder
public class ResultSetSnapshot {

    public static final String STORAGE_INLINE = "INLINE";
    public static final String STORAGE_REF = "REF";
    public static final String STATUS_VALID = "VALID";
    public static final String STATUS_STALE = "STALE";

    /** 结果集编号(rs- 前缀) */
    private String resultSetId;

    /** 产生它的查询编号(q- 前缀) */
    private String queryId;

    /** 会话编号 */
    private Long conversationId;

    /** 知识库编号(产生该结果集的知识库; 引用重校验用, CQ-38) */
    private Long kbId;

    /** 知识领域编码(如 PATENT; 引用重校验用, CQ-38) */
    private String domainCode;

    /** 实体类型(如 PATENT_DOCUMENT) */
    private String entityType;

    /** 实体总数(逻辑集合完整数) */
    private Integer entityCount;

    /** 存储模式: INLINE / REF */
    private String storageMode;

    /** 保序实体 id 列表(INLINE 时有效; REF 时为 null, 用 scopeDescriptor 重建) */
    private List<Long> orderedEntityIds;

    /** 范围描述(JSON: kbId/domainCode/scopeType/filters/sort), REF materialize 用 */
    private String scopeDescriptor;

    /** 知识修订标记(版本/发布时间, 用于 STALE 判定) */
    private String knowledgeRevision;

    /** 状态: VALID / STALE */
    private String status;

    /** 是否截断(结果数超过上限) */
    private Boolean truncated;

    /** 存在有效字段值的实体数(PARTIAL 统计) */
    private Integer validValueCount;

    /** 缺少字段值的实体数(PARTIAL 统计) */
    private Integer missingValueCount;

    /** 是否存在同一字段多个当前值冲突 */
    private Boolean conflict;

    public static ResultSetSnapshot fromDO(AiChatResultSetDO row) {
        if (row == null) {
            return null;
        }
        List<Long> ids = null;
        if (StrUtil.isNotBlank(row.getOrderedEntityIds())) {
            ids = JSONUtil.toList(row.getOrderedEntityIds(), Long.class);
        }
        return ResultSetSnapshot.builder()
                .resultSetId(row.getResultSetId())
                .queryId(row.getQueryId())
                .conversationId(row.getConversationId())
                .kbId(row.getKbId())
                .domainCode(row.getDomainCode())
                .entityType(row.getEntityType())
                .entityCount(row.getEntityCount())
                .storageMode(row.getStorageMode())
                .orderedEntityIds(ids)
                .scopeDescriptor(row.getScopeDescriptor())
                .knowledgeRevision(row.getKnowledgeRevision())
                .status(row.getStatus())
                .truncated(row.getTruncated())
                .validValueCount(row.getValidValueCount())
                .missingValueCount(row.getMissingValueCount())
                .conflict(row.getConflict())
                .build();
    }

    public AiChatResultSetDO toDO() {
        AiChatResultSetDO row = new AiChatResultSetDO();
        row.setResultSetId(resultSetId);
        row.setQueryId(queryId);
        row.setConversationId(conversationId);
        row.setKbId(kbId);
        row.setDomainCode(domainCode);
        row.setEntityType(entityType);
        row.setEntityCount(entityCount);
        row.setStorageMode(storageMode);
        row.setOrderedEntityIds(orderedEntityIds == null ? null : JSONUtil.toJsonStr(orderedEntityIds));
        row.setScopeDescriptor(scopeDescriptor);
        row.setKnowledgeRevision(knowledgeRevision);
        row.setStatus(status);
        row.setTruncated(Boolean.TRUE.equals(truncated));
        row.setValidValueCount(validValueCount);
        row.setMissingValueCount(missingValueCount);
        row.setConflict(Boolean.TRUE.equals(conflict));
        return row;
    }

}
