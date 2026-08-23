package cn.iocoder.yudao.module.chat.controller.admin.conversation.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "管理后台 - 会话消息 Response VO")
@Data
public class MessageVO {

    @Schema(description = "消息编号")
    private Long id;

    @Schema(description = "角色: USER 用户 / AI 机器人 / SYSTEM 系统")
    private String role;

    @Schema(description = "消息内容")
    private String content;

    @Schema(description = "引用证据 chunkId 列表(JSON 字符串解析, 为空返回空列表)")
    private List<String> citations;

    @Schema(description = "意图(USER 消息识别结果)")
    private String intent;

    @Schema(description = "置信度(0~1, 4 位小数, AI 消息)")
    private BigDecimal confidence;

    @Schema(description = "证据链路追踪号(证据评估 traceId, AI 消息)")
    private String traceId;

    @Schema(description = "统一主追踪号(q- 前缀, AI 消息)")
    private String queryTraceId;

    @Schema(description = "权威检索路由(RULE/EXACT_METADATA/EXACT_CLAIM/SCOPED_RAG/HYBRID_RAG/ABSTAIN, AI 消息)")
    private String route;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "证据快照列表(P0-08: 历史会话刷新后 Evidence 不丢; AI 消息)")
    private List<EvidenceVO> evidence;

    @Schema(description = "证据快照(统一 Evidence DTO 的历史持久化形态)")
    @Data
    public static class EvidenceVO {

        @Schema(description = "证据序号(0-based, 对应 [Cn] 的 n-1)")
        private Integer evidenceIndex;

        @Schema(description = "引用标注(如 C1)")
        private String citationLabel;

        @Schema(description = "来源文档编号")
        private Long documentId;

        @Schema(description = "版本编号")
        private Long versionId;

        @Schema(description = "片段编号")
        private Long chunkId;

        @Schema(description = "知识库编号")
        private Long kbId;

        @Schema(description = "知识领域编码")
        private String domainCode;

        @Schema(description = "片段类型(权利要求书/说明书/著录信息 等)")
        private String sectionType;

        @Schema(description = "片段小节标题")
        private String sectionTitle;

        @Schema(description = "权利要求编号")
        private String claimNo;

        @Schema(description = "起始页码")
        private Integer pageStart;

        @Schema(description = "结束页码")
        private Integer pageEnd;

        @Schema(description = "申请号")
        private String applicationNo;

        @Schema(description = "公布号")
        private String publicationNo;

        @Schema(description = "来源文档名")
        private String documentName;

        @Schema(description = "版本号")
        private String versionNo;

        @Schema(description = "证据原文快照")
        private String contentSnapshot;

        @Schema(description = "归一化得分(0~1)")
        private BigDecimal score;
    }

}
