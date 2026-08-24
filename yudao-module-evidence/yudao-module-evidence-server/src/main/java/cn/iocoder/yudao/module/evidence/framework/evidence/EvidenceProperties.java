package cn.iocoder.yudao.module.evidence.framework.evidence;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 证据平台业务配置
 * <p>
 * 绑定 yudao.evidence.* 配置项(kebab-case 自动映射 camelCase), 供充分性判定/去重/Claim 验证等环节读取,
 * 全部阈值与权重来自配置, 代码不硬编码。
 */
@Data
@ConfigurationProperties(prefix = "yudao.evidence")
public class EvidenceProperties {

    /** 充分性判定配置 */
    private Sufficiency sufficiency = new Sufficiency();

    /** 去重配置 */
    private Dedup dedup = new Dedup();

    /** Claim 验证配置 */
    private Claim claim = new Claim();

    /** 槽位检测配置 */
    private Slot slot = new Slot();

    /** 生成管线配置 */
    private Pipeline pipeline = new Pipeline();

    /** 语义执行配置(CQ-38) */
    private Semantics semantics = new Semantics();

    /**
     * 语义执行配置(PER_ENTITY_SEMANTIC/CROSS_ENTITY_SEMANTIC)
     */
    @Data
    public static class Semantics {

        /** PER_ENTITY_SEMANTIC 单轮最多实体数(超限 CLARIFY 要求缩小, 默认 10) */
        private int maxSemanticEntities = 10;

    }

    /**
     * 生成管线配置(P0-11: 整体查询 Deadline, 禁止单个请求无限拖 2~3 分钟)
     */
    @Data
    public static class Pipeline {

        /** 生成/验证总时限(ms, 默认 60s; 超时停止继续 repair, 返回 degraded=true) */
        private Long deadlineMs = 60_000L;

    }

    /**
     * 槽位检测配置
     */
    @Data
    public static class Slot {

        /** 总开关(默认 true; 关掉则跳过检测走原流程) */
        private Boolean enabled = true;

    }

    /**
     * 充分性判定配置
     */
    @Data
    public static class Sufficiency {

        /** 可作答判定阈值: 证据充分度得分 &gt;= 该值视为可作答 */
        private Double answerThreshold = 0.75;

        /** 转人工咨询阈值: 得分 &gt;= 该值视为可转人工咨询 */
        private Double consultThreshold = 0.5;

        /** 最少证据条数: 证据数低于该值判定证据不足 */
        private Integer minEvidenceCount = 2;

        /** 实体一致性校验开关: true = 问题实体必须有证据覆盖, 否则产品不匹配 */
        private Boolean entityConsistency = true;

        /** 冲突阻断作答开关: true = 存在冲突证据时阻断作答 */
        private Boolean conflictBlock = true;

        /** 融合权重(需归一化, 和应为 1.0; 判定时校验, 非 1.0 告警并重新归一化) */
        private Weights weights = new Weights();

    }

    /**
     * 置信度融合权重
     */
    @Data
    public static class Weights {

        /** 最高证据分权重 */
        private double topScore = 0.5;

        /** 证据条数权重 */
        private double evidenceCount = 0.3;

        /** 实体覆盖率权重 */
        private double entityCoverage = 0.2;

    }

    /**
     * 去重配置
     */
    @Data
    public static class Dedup {

        /** 相似度阈值: 相似度 &gt;= 该值视为重复证据并合并 */
        private Double similarityThreshold = 0.85;

    }

    /**
     * Claim 验证配置
     */
    @Data
    public static class Claim {

        /** 最大重试次数 */
        private Integer maxRetry = 2;

    }

}
