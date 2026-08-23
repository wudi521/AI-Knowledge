package cn.iocoder.yudao.module.knowledge.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 专利文档精确定位请求。
 *
 * 用于检索侧把申请号/公布号先解析成真实 documentId，再进入 Exact/Scoped 检索，
 * 避免只依赖语义召回导致跨专利污染。
 */
@Data
public class PatentDocumentLookupReqDTO {

    /** 已完成权限裁剪后的知识库范围，不能为空。 */
    private List<Long> kbIds;

    /** 申请号，例如 202311042981.1。 */
    private String applicationNo;

    /** 公布号，例如 CN 122604134 A。 */
    private String publicationNo;
}
