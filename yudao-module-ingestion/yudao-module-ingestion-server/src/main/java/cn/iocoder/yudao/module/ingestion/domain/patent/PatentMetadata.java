package cn.iocoder.yudao.module.ingestion.domain.patent;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 专利著录信息(与 ai_document.domain_metadata JSON 对应)
 */
@Data
public class PatentMetadata {

    public static final String DOMAIN_CODE = "PATENT";

    private String domainCode = DOMAIN_CODE;

    /** 申请号(去空格: 202311344028.2) */
    private String applicationNo;

    /** 申请公布号(如 CN 122621758 A) */
    private String publicationNo;

    /** 申请日(YYYY-MM-DD) */
    private String filingDate;

    /** 申请公布日(YYYY-MM-DD) */
    private String publicationDate;

    /** 发明名称 */
    private String title;

    /** 申请人(可多个) */
    private List<String> applicants = new ArrayList<>();

    /** 发明人(可多个) */
    private List<String> inventors = new ArrayList<>();

    /** 专利代理机构 */
    private String agency;

    /** 专利代理师 */
    private List<String> agents = new ArrayList<>();

    /** IPC 分类号(可多个) */
    private List<String> ipcCodes = new ArrayList<>();

    /** 摘要 */
    private String abstractText;

    /** 权利要求数量 */
    private Integer claimCount;

    /** 文献类型(发明专利申请公布等) */
    private String sourceType;

    /** 提取器版本 */
    private String extractorVersion = "patent-mvp-1.0";
}
