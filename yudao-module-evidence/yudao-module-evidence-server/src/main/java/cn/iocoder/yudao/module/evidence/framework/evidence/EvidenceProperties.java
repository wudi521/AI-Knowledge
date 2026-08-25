package cn.iocoder.yudao.module.evidence.framework.evidence;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 证据平台业务配置。 */
@Data
@ConfigurationProperties(prefix = "yudao.evidence")
public class EvidenceProperties {

    private Sufficiency sufficiency = new Sufficiency();
    private Dedup dedup = new Dedup();
    private Claim claim = new Claim();
    private Slot slot = new Slot();
    private Pipeline pipeline = new Pipeline();
    private Semantics semantics = new Semantics();
    private Agent agent = new Agent();

    @Data
    public static class Agent {
        /** V3 / AGENT / AGENT_WITH_V3_FALLBACK。 */
        private String mode = "V3";
        private int maxSteps = 6;
        private int maxLlmCalls = 6;
        private long maxElapsedMs = 15_000L;
        private int capabilityTimeoutThreads = 8;
        private String environment = "default";
        private boolean writeAllowed = false;
        /** 非空时只暴露这些能力。 */
        private java.util.Set<String> enabledCapabilities = new java.util.LinkedHashSet<>();
        /** 始终隐藏这些能力。 */
        private java.util.Set<String> disabledCapabilities = new java.util.LinkedHashSet<>();
    }

    @Data
    public static class Semantics {
        /** 超限直接要求缩小范围，禁止无界 per-entity fan-out。 */
        private int maxSemanticEntities = 10;
    }

    @Data
    public static class Pipeline {
        /** Generate + Verify + Repair 总时限；Structured/ExactText 不进入模型生成管线。 */
        private Long deadlineMs = 20_000L;
    }

    @Data
    public static class Slot {
        private Boolean enabled = true;
    }

    @Data
    public static class Sufficiency {
        private Double answerThreshold = 0.75;
        private Double consultThreshold = 0.5;
        private Integer minEvidenceCount = 2;
        private Boolean entityConsistency = true;
        private Boolean conflictBlock = true;
        private Weights weights = new Weights();
    }

    @Data
    public static class Weights {
        private double topScore = 0.5;
        private double evidenceCount = 0.3;
        private double entityCoverage = 0.2;
    }

    @Data
    public static class Dedup {
        private Double similarityThreshold = 0.85;
    }

    @Data
    public static class Claim {
        /** 首次验证失败最多允许一次修复，不允许模型循环拖长请求。 */
        private Integer maxRetry = 1;
    }
}
