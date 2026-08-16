package cn.iocoder.yudao.module.knowledge.enums.version;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 文档版本状态(对应 ai_doc_version.status, BR-005 五态)
 */
@Getter
@AllArgsConstructor
public enum VersionStatusEnum {

    DRAFT("DRAFT", "草稿"),
    REVIEW("REVIEW", "审核中"),
    PUBLISHED("PUBLISHED", "已发布"),
    EXPIRED("EXPIRED", "已过期"),
    ARCHIVED("ARCHIVED", "已归档");

    private final String status;
    private final String name;

}
