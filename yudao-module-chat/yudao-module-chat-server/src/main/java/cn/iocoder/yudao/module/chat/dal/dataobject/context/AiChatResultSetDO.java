package cn.iocoder.yudao.module.chat.dal.dataobject.context;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 多轮查询结果集快照 DO(ai_chat_result_set)
 * <p>
 * 结构化/实体查询成功后形成, 保序保存实体 id; 大结果集用 REF(仅存 scope 描述 + 知识修订标记,
 * 按需 materialize)。查询上下文绑定 current-valid 实体; 历史 evidence 快照不随版本漂移。
 */
@TableName("ai_chat_result_set")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiChatResultSetDO extends TenantBaseDO {

    /** 编号 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 结果集编号(rs- 前缀, UNIQUE) */
    private String resultSetId;

    /** 产生它的查询编号(q- 前缀) */
    private String queryId;

    /** 会话编号 */
    private Long conversationId;

    /** 实体类型(如 PATENT_DOCUMENT) */
    private String entityType;

    /** 实体总数(逻辑集合完整数) */
    private Integer entityCount;

    /** 存储模式: INLINE 内联 ids / REF 仅存描述按需重建 */
    private String storageMode;

    /** 保序实体 id 列表(INLINE 时 JSON 数组) */
    private String orderedEntityIds;

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

}
