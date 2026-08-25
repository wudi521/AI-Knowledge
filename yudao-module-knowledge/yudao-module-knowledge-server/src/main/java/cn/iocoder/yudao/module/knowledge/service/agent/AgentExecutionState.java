package cn.iocoder.yudao.module.knowledge.service.agent;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Agent 单次请求的执行状态。
 *
 * <p>{@code originalGoal} 只在构造时赋值，刻意不提供 setter，避免检索候选、查询改写
 * 或后续 Planner 覆盖用户的原始目标。</p>
 */
public final class AgentExecutionState {

    private final String originalGoal;
    private final long startedAtMillis;
    private final Set<String> capabilityCallFingerprints = new LinkedHashSet<>();

    private String currentSubGoal;
    private int step;
    private int llmCalls;
    private String lastProgressHash;
    private AgentStopReason stopReason;
    private EvidenceCoverage evidenceCoverage = EvidenceCoverage.NONE;

    public AgentExecutionState(String originalGoal) {
        String normalized = originalGoal == null ? "" : originalGoal.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("originalGoal must not be blank");
        }
        this.originalGoal = normalized;
        this.currentSubGoal = normalized;
        this.startedAtMillis = System.currentTimeMillis();
    }

    public String getOriginalGoal() {
        return originalGoal;
    }

    public String getCurrentSubGoal() {
        return currentSubGoal;
    }

    public void setCurrentSubGoal(String currentSubGoal) {
        this.currentSubGoal = currentSubGoal;
    }

    public int getStep() {
        return step;
    }

    public int incrementStep() {
        return ++step;
    }

    public int getLlmCalls() {
        return llmCalls;
    }

    public int incrementLlmCalls() {
        return ++llmCalls;
    }

    public long getStartedAtMillis() {
        return startedAtMillis;
    }

    public long elapsedMs() {
        return Math.max(0L, System.currentTimeMillis() - startedAtMillis);
    }

    public boolean hasCapabilityCallFingerprint(String fingerprint) {
        return capabilityCallFingerprints.contains(fingerprint);
    }

    public boolean addCapabilityCallFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            throw new IllegalArgumentException("fingerprint must not be blank");
        }
        return capabilityCallFingerprints.add(fingerprint);
    }

    public Set<String> getCapabilityCallFingerprints() {
        return Collections.unmodifiableSet(capabilityCallFingerprints);
    }

    public String getLastProgressHash() {
        return lastProgressHash;
    }

    public boolean markProgress(String progressHash) {
        if (progressHash == null || progressHash.isBlank()) {
            return false;
        }
        boolean changed = !Objects.equals(lastProgressHash, progressHash);
        lastProgressHash = progressHash;
        return changed;
    }

    public AgentStopReason getStopReason() {
        return stopReason;
    }

    public void stop(AgentStopReason stopReason) {
        this.stopReason = Objects.requireNonNull(stopReason, "stopReason");
    }

    public boolean isStopped() {
        return stopReason != null;
    }

    public EvidenceCoverage getEvidenceCoverage() {
        return evidenceCoverage;
    }

    public void setEvidenceCoverage(EvidenceCoverage evidenceCoverage) {
        this.evidenceCoverage = Objects.requireNonNull(evidenceCoverage, "evidenceCoverage");
    }

}
