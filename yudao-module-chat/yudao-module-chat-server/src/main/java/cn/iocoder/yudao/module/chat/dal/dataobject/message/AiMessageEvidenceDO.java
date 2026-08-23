package cn.iocoder.yudao.module.chat.dal.dataobject.message;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * AI 会话消息 - 证据快照(ai_message_evidence)
 * <p>
 * 历史回答引用的是当时版本(如 V1)的证据, 即使当前知识已升级到 V3, 历史会话仍须能说明
 * "当时回答依据 V1" —— 因此持久化证据原文/元数据快照, 不随版本升级漂移。
 */
@TableName("ai_message_evidence")
@Data
@EqualsAndHashCode(callSuper = true)
public class AiMessageEvidenceDO extends TenantBaseDO {

    /** 编号 */
    private Long id;

    /** 消息编号 */
    private Long messageId;

    /** 证据在列表中的序号(0-based, 对应 [Cn] 的 n-1) */
    private Integer evidenceIndex;

    /** 引用标注(如 C1, 对应回答中的 [C1]) */
    private String citationLabel;

    /** 来源文档编号 */
    private Long documentId;

    /** 版本编号 */
    private Long versionId;

    /** 片段编号 */
    private Long chunkId;

    /** 知识库编号 */
    private Long kbId;

    /** 知识领域编码 */
    private String domainCode;

    /** 片段类型(权利要求书/说明书/著录信息 等) */
    private String sectionType;

    /** 片段小节标题 */
    private String sectionTitle;

    /** 权利要求编号 */
    private String claimNo;

    /** 起始页码 */
    private Integer pageStart;

    /** 结束页码 */
    private Integer pageEnd;

    /** 申请号 */
    private String applicationNo;

    /** 公布号 */
    private String publicationNo;

    /** 来源文档名 */
    private String documentName;

    /** 版本号 */
    private String versionNo;

    /** 证据原文快照 */
    private String contentSnapshot;

    /** 元数据快照(JSON, 内部保留) */
    private String metadataSnapshot;

    /** 归一化得分(0~1) */
    private BigDecimal score;

}
