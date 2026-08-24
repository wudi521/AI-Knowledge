package cn.iocoder.yudao.module.evidence.service.trace;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.evidence.api.dto.StructuredResultDTO;
import cn.iocoder.yudao.module.evidence.controller.admin.evaluate.vo.EvidenceEvaluateRespVO;
import cn.iocoder.yudao.module.retrieval.api.dto.QueryStageTimingDTO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Query Execution Inspector 第一版。
 *
 * <p>不改变任何查询决策，只把 Evidence 已经产生的真实结果补充到 Trace stage 的
 * inputSummary / outputSummary，供检索评测页和对话工作台回放。</p>
 *
 * <p>安全边界：不记录系统 Prompt、隐藏推理、Token、Authorization、Embedding 浮点数组；
 * 只记录用户问题、结构化计划摘要、查询范围、候选/证据摘要、最终回答与验证 verdict。</p>
 */
@Component
public class EvidenceTraceInspector {

    private static final int SUMMARY_LIMIT = 950; // DB ai_query_trace_stage varchar(1000) 留安全余量
    private static final int ITEM_LIMIT = 5;

    public EvidenceEvaluateRespVO enrich(EvidenceEvaluateRespVO resp, List<Long> kbIds, String domainCode) {
        if (resp == null) return null;
        List<QueryStageTimingDTO> stages = resp.getStages() == null
                ? new ArrayList<>() : new ArrayList<>(resp.getStages());

        // Structured 旧链只有 METRIC_RESOLVE，没有把字段/过滤解析单独表现出来。
        // Inspector 只补“可观察节点”，不伪造执行耗时，也不改变查询结果。
        if ("STRUCTURED_QUERY".equals(resp.getRoute())) {
            ensureStageBefore(stages, "STRUCTURED_EXECUTE", "FIELD_RESOLVE");
            if (looksLikeFilterQuery(resp.getQuery())) {
                ensureStageBefore(stages, "STRUCTURED_EXECUTE", "FILTER_RESOLVE");
            }
        }

        renumber(stages);
        for (QueryStageTimingDTO stage : stages) {
            if (stage == null || StrUtil.isBlank(stage.getStage())) continue;
            if (StrUtil.isBlank(stage.getInputSummary())) {
                stage.setInputSummary(limit(buildInput(stage.getStage(), resp, kbIds, domainCode)));
            }
            if (StrUtil.isBlank(stage.getOutputSummary())) {
                stage.setOutputSummary(limit(buildOutput(stage.getStage(), resp, kbIds, domainCode)));
            }
        }
        resp.setStages(stages);
        return resp;
    }

    private String buildInput(String stage, EvidenceEvaluateRespVO resp, List<Long> kbIds, String domainCode) {
        String query = StrUtil.blankToDefault(resp.getQuery(), "-");
        return switch (stage) {
            case "ANALYZE" -> "用户问题=" + quote(query)
                    + "; domain=" + value(domainCode)
                    + "; historyTurns=" + (resp.getHistory() == null ? 0 : resp.getHistory().size());
            case "ROUTE", "PLAN" -> "问题分析=" + analysisSummary(resp)
                    + "; requestedKbIds=" + list(kbIds);
            case "CONTEXT_RESOLVE" -> "historyTurns=" + (resp.getHistory() == null ? 0 : resp.getHistory().size())
                    + "; query=" + quote(query);
            case "SCOPE_RESOLVE", "SCOPE_FILTER" -> "requestedKbIds=" + list(kbIds)
                    + "; domain=" + value(domainCode)
                    + "; scopeHint=" + structuredScope(resp);
            case "FIELD_RESOLVE" -> "从用户问题识别要返回/过滤的结构化字段: " + quote(query);
            case "METRIC_RESOLVE" -> "从用户问题识别统计指标/运算: " + quote(query);
            case "FILTER_RESOLVE" -> "从用户问题识别过滤条件: " + quote(query);
            case "STRUCTURED_EXECUTE" -> "plan={" + structuredPlanSummary(resp) + "}";
            case "BM25" -> "query=" + quote(query) + "; kbIds=" + list(kbIds)
                    + "; semanticScope=" + structuredScope(resp);
            case "VECTOR" -> "query=" + quote(query) + "; kbIds=" + list(kbIds)
                    + "; 不展示 embedding 向量值";
            case "FUSION" -> "bm25Hits=" + channel(resp, "bm25")
                    + "; vectorHits=" + channel(resp, "vector");
            case "RERANK" -> "query=" + quote(query)
                    + "; 当前响应可见证据=" + evidenceCount(resp)
                    + "（第一版尚未透出 rerank 前完整候选集）";
            case "DOC_LOOKUP" -> "对候选 chunk 补全文档/版本/领域元数据";
            case "EXACT_TEXT_RETRIEVE" -> "原文精确检索问题=" + quote(query)
                    + "; kbIds=" + list(kbIds);
            case "PER_ENTITY_RETRIEVE", "CROSS_ENTITY_RETRIEVE" -> "query=" + quote(query)
                    + "; entityIds=" + structuredEntityIds(resp);
            case "CROSS_ENTITY_COVERAGE" -> "待比较实体=" + structuredEntityIds(resp)
                    + "; coveragePolicy=ALL(比较类查询)";
            case "EVIDENCE" -> "检索结果进入证据去重/冲突/充分性判定; currentEvidence=" + evidenceCount(resp);
            case "GENERATE" -> "用户问题=" + quote(query)
                    + "; evidence=" + evidenceRefSummary(resp);
            case "VERIFY" -> "待验证回答=" + quote(abbreviate(resp.getAnswer(), 360))
                    + "; claims=" + claimCount(resp);
            case "REPAIR" -> "对未通过证据验证的回答做最多一次受限修复";
            case "ANSWER" -> "answerable=" + resp.getAnswerable()
                    + "; reasonCode=" + value(resp.getReasonCode());
            default -> null;
        };
    }

    private String buildOutput(String stage, EvidenceEvaluateRespVO resp, List<Long> kbIds, String domainCode) {
        if (isSkipped(stage, resp)) {
            return skippedReason(stage, resp);
        }
        return switch (stage) {
            case "ANALYZE" -> analysisSummary(resp);
            case "ROUTE" -> "route=" + value(resp.getRoute())
                    + "; executionMode=" + value(resp.getExecutionMode())
                    + "; intent=" + value(resp.getIntent() != null ? resp.getIntent()
                    : resp.getAnalysis() != null ? resp.getAnalysis().getIntent() : null);
            case "PLAN" -> "queryClass=" + planClass(resp)
                    + "; route=" + value(resp.getRoute())
                    + "; executionMode=" + value(resp.getExecutionMode())
                    + "; structured={" + structuredPlanSummary(resp) + "}"
                    + (resp.getReasonCode() != null ? "; reasonCode=" + resp.getReasonCode() : "");
            case "CONTEXT_RESOLVE" -> resp.getHistory() == null || resp.getHistory().isEmpty()
                    ? "无历史上下文/单轮查询" : "已提供 " + resp.getHistory().size() + " 轮历史上下文";
            case "SCOPE_RESOLVE", "SCOPE_FILTER" -> "effectiveKbIds=" + list(kbIds)
                    + "; domain=" + value(domainCode)
                    + "; " + structuredScope(resp)
                    + "; entityIds=" + structuredEntityIds(resp);
            case "FIELD_RESOLVE" -> fieldSummary(resp);
            case "METRIC_RESOLVE" -> metricSummary(resp);
            case "FILTER_RESOLVE" -> filterSummary(resp);
            case "STRUCTURED_EXECUTE" -> structuredExecuteSummary(resp);
            case "BM25" -> "bm25Hits=" + channel(resp, "bm25")
                    + "; 最终保留证据=" + evidenceByChannel(resp, "bm25");
            case "VECTOR" -> "vectorHits=" + channel(resp, "vector")
                    + "; 最终保留证据=" + evidenceByChannel(resp, "vector");
            case "FUSION" -> "fusedCandidates=" + channel(resp, "fused")
                    + "; 当前最终证据=" + evidenceRefSummary(resp);
            case "RERANK" -> "最终 Top 证据=" + evidenceScoreSummary(resp);
            case "DOC_LOOKUP" -> "distinctDocuments=" + distinctDocumentCount(resp)
                    + "; documents=" + documentSummary(resp);
            case "EXACT_TEXT_RETRIEVE", "PER_ENTITY_RETRIEVE", "CROSS_ENTITY_RETRIEVE" ->
                    "evidenceCount=" + evidenceCount(resp) + "; evidence=" + evidenceScoreSummary(resp);
            case "CROSS_ENTITY_COVERAGE" -> "entityIds=" + structuredEntityIds(resp)
                    + "; evidenceDocuments=" + distinctDocumentCount(resp)
                    + "; result=" + (Boolean.TRUE.equals(resp.getAnswerable()) ? "PASS" : "CHECK/FAIL")
                    + (resp.getReasonCode() != null ? "; reasonCode=" + resp.getReasonCode() : "");
            case "EVIDENCE" -> "evidenceCount=" + evidenceCount(resp)
                    + "; conflicts=" + (resp.getConflicts() == null ? 0 : resp.getConflicts().size())
                    + "; answerable=" + resp.getAnswerable()
                    + "; confidence=" + value(resp.getConfidence());
            case "GENERATE" -> "模型最终输出=" + quote(abbreviate(resp.getAnswer(), 650));
            case "VERIFY" -> verifySummary(resp);
            case "REPAIR" -> "repair 后最终 answerable=" + resp.getAnswerable()
                    + "; claimFail=" + resp.getClaimFail();
            case "ANSWER" -> finalAnswerSummary(resp);
            default -> null;
        };
    }

    private String structuredPlanSummary(EvidenceEvaluateRespVO resp) {
        StructuredResultDTO s = resp.getStructuredResult();
        if (s == null) return "未透出 structuredResult";
        return "queryType=" + value(s.getQueryType())
                + ", scope=" + value(s.getScopeType())
                + ", field=" + value(s.getFieldCode())
                + ", metric=" + value(s.getMetricCode())
                + ", operation=" + value(s.getOperation())
                + ", entityCount=" + value(s.getEntityCount())
                + ", truncated=" + value(s.getTruncated());
    }

    private String structuredExecuteSummary(EvidenceEvaluateRespVO resp) {
        StructuredResultDTO s = resp.getStructuredResult();
        if (s == null) {
            return "未返回 structuredResult; reasonCode=" + value(resp.getReasonCode())
                    + "; refusal=" + quote(abbreviate(resp.getRefusalReason(), 300));
        }
        return "result={" + structuredPlanSummary(resp)
                + ", entityIds=" + list(s.getEntityIds())
                + ", entityKeys=" + listString(s.getEntityKeys())
                + "}; renderedAnswer=" + quote(abbreviate(resp.getAnswer(), 420));
    }

    private String fieldSummary(EvidenceEvaluateRespVO resp) {
        StructuredResultDTO s = resp.getStructuredResult();
        if (s == null) return "字段解析结果未透出";
        if (StrUtil.isNotBlank(s.getFieldCode())) return "fieldCode=" + s.getFieldCode();
        if (s.getEntityKeys() != null && !s.getEntityKeys().isEmpty()) {
            return "多字段/实体投影已执行，但当前 StructuredResultDTO 尚未透出 projections 列表; entityKeys="
                    + listString(s.getEntityKeys());
        }
        return "当前响应未透出 fieldCode/projections；如字段选择错误，需要继续下沉 QueryPlan.projections";
    }

    private String metricSummary(EvidenceEvaluateRespVO resp) {
        StructuredResultDTO s = resp.getStructuredResult();
        if (s == null) return "metric=无/未透出";
        return "metric=" + value(s.getMetricCode()) + "; operation=" + value(s.getOperation());
    }

    private String filterSummary(EvidenceEvaluateRespVO resp) {
        String filters = structuredEvidenceFilters(resp);
        if (StrUtil.isNotBlank(filters)) return "structuredFilters=" + filters;
        return "原问题存在过滤语义，但当前响应尚未透出 StructuredQueryPlan.filterExpression；"
                + "若此节点判断错误，说明下一步需要把类型化 FilterTree 直接写入 Trace。";
    }

    private String structuredEvidenceFilters(EvidenceEvaluateRespVO resp) {
        if (resp.getEvidence() == null) return null;
        return resp.getEvidence().stream()
                .filter(e -> e != null && "STRUCTURED_RESULT".equals(e.getEvidenceType()))
                .map(EvidenceEvaluateRespVO.EvidenceItemVO::getFilters)
                .filter(StrUtil::isNotBlank)
                .findFirst().orElse(null);
    }

    private String analysisSummary(EvidenceEvaluateRespVO resp) {
        if (resp.getAnalysis() == null) {
            return "intent=" + value(resp.getIntent()) + "; route=" + value(resp.getRoute())
                    + "; executionMode=" + value(resp.getExecutionMode()) + "; analyzer=deterministic/structured";
        }
        return "intent=" + value(resp.getAnalysis().getIntent())
                + "; route=" + value(resp.getAnalysis().getRoute())
                + "; entities=" + listString(resp.getAnalysis().getEntities())
                + "; rewrites=" + listString(resp.getAnalysis().getRewrites())
                + "; subQuestions=" + listString(resp.getAnalysis().getSubQuestions())
                + "; success=" + resp.getAnalysis().getSuccess();
    }

    private String evidenceRefSummary(EvidenceEvaluateRespVO resp) {
        if (resp.getEvidence() == null || resp.getEvidence().isEmpty()) return "[]";
        return resp.getEvidence().stream().limit(ITEM_LIMIT).map(e -> {
            if (e == null) return "null";
            return "{chunk=" + value(e.getChunkId())
                    + ",doc=" + value(e.getDocumentId())
                    + ",name=" + abbreviate(e.getDocumentName(), 60)
                    + ",channels=" + listString(e.getChannels()) + "}";
        }).collect(Collectors.joining(",", "[", resp.getEvidence().size() > ITEM_LIMIT ? ",...]" : "]"));
    }

    private String evidenceScoreSummary(EvidenceEvaluateRespVO resp) {
        if (resp.getEvidence() == null || resp.getEvidence().isEmpty()) return "[]";
        return resp.getEvidence().stream().limit(ITEM_LIMIT).map(e -> {
            if (e == null) return "null";
            return "{chunk=" + value(e.getChunkId())
                    + ",doc=" + value(e.getDocumentId())
                    + ",score=" + value(e.getScore())
                    + ",name=" + abbreviate(e.getDocumentName(), 50) + "}";
        }).collect(Collectors.joining(",", "[", resp.getEvidence().size() > ITEM_LIMIT ? ",...]" : "]"));
    }

    private String evidenceByChannel(EvidenceEvaluateRespVO resp, String channel) {
        if (resp.getEvidence() == null || resp.getEvidence().isEmpty()) return "[]";
        List<EvidenceEvaluateRespVO.EvidenceItemVO> filtered = resp.getEvidence().stream()
                .filter(e -> e != null && e.getChannels() != null && e.getChannels().contains(channel))
                .limit(ITEM_LIMIT).toList();
        if (filtered.isEmpty()) return "[]（注意：这里只能看到最终存活证据，不等于原始召回候选）";
        return filtered.stream().map(e -> "{chunk=" + value(e.getChunkId())
                        + ",doc=" + value(e.getDocumentId()) + ",score=" + value(e.getScore()) + "}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private String documentSummary(EvidenceEvaluateRespVO resp) {
        if (resp.getEvidence() == null) return "[]";
        Set<String> docs = new LinkedHashSet<>();
        for (EvidenceEvaluateRespVO.EvidenceItemVO e : resp.getEvidence()) {
            if (e == null) continue;
            docs.add("{id=" + value(e.getDocumentId()) + ",name=" + abbreviate(e.getDocumentName(), 80) + "}");
            if (docs.size() >= ITEM_LIMIT) break;
        }
        return docs.toString();
    }

    private int distinctDocumentCount(EvidenceEvaluateRespVO resp) {
        if (resp.getEvidence() == null) return 0;
        return (int) resp.getEvidence().stream().filter(e -> e != null && e.getDocumentId() != null)
                .map(EvidenceEvaluateRespVO.EvidenceItemVO::getDocumentId).distinct().count();
    }

    private String verifySummary(EvidenceEvaluateRespVO resp) {
        if (resp.getClaims() == null || resp.getClaims().isEmpty()) {
            return "claims=[]; claimFail=" + resp.getClaimFail()
                    + "; verificationDegraded=" + resp.getVerificationDegraded();
        }
        String claims = resp.getClaims().stream().limit(ITEM_LIMIT)
                .map(c -> "{" + c.getVerdict() + ":" + abbreviate(c.getText(), 120)
                        + ",evidenceIndex=" + c.getEvidenceIndex() + "}")
                .collect(Collectors.joining(",", "[", resp.getClaims().size() > ITEM_LIMIT ? ",...]" : "]"));
        return "claims=" + claims + "; claimFail=" + resp.getClaimFail()
                + "; verificationDegraded=" + resp.getVerificationDegraded();
    }

    private String finalAnswerSummary(EvidenceEvaluateRespVO resp) {
        if (Boolean.TRUE.equals(resp.getAnswerable())) {
            return "最终回答=" + quote(abbreviate(resp.getAnswer(), 700));
        }
        if (StrUtil.isNotBlank(resp.getClarifyQuestion())) {
            return "需要澄清=" + quote(abbreviate(resp.getClarifyQuestion(), 700))
                    + "; reasonCode=" + value(resp.getReasonCode());
        }
        return "拒绝回答=" + quote(abbreviate(resp.getRefusalReason(), 700))
                + "; reasonCode=" + value(resp.getReasonCode());
    }

    private String structuredScope(EvidenceEvaluateRespVO resp) {
        StructuredResultDTO s = resp.getStructuredResult();
        return s == null ? "scope=未透出" : "scope=" + value(s.getScopeType());
    }

    private String structuredEntityIds(EvidenceEvaluateRespVO resp) {
        StructuredResultDTO s = resp.getStructuredResult();
        return s == null ? "[]" : list(s.getEntityIds());
    }

    private int evidenceCount(EvidenceEvaluateRespVO resp) {
        return resp.getEvidence() == null ? 0 : resp.getEvidence().size();
    }

    private int claimCount(EvidenceEvaluateRespVO resp) {
        return resp.getClaims() == null ? 0 : resp.getClaims().size();
    }

    private String channel(EvidenceEvaluateRespVO resp, String name) {
        if (resp.getChannels() == null) return "0";
        return switch (name) {
            case "bm25" -> String.valueOf(resp.getChannels().getBm25() == null ? 0 : resp.getChannels().getBm25());
            case "vector" -> String.valueOf(resp.getChannels().getVector() == null ? 0 : resp.getChannels().getVector());
            case "fused" -> String.valueOf(resp.getChannels().getFused() == null ? 0 : resp.getChannels().getFused());
            default -> "0";
        };
    }

    private boolean isSkipped(String stage, EvidenceEvaluateRespVO resp) {
        if (resp.getStages() == null) return false;
        return resp.getStages().stream().anyMatch(s -> s != null && stage.equals(s.getStage()) && Boolean.TRUE.equals(s.getSkipped()));
    }

    private String skippedReason(String stage, EvidenceEvaluateRespVO resp) {
        if ("STRUCTURED_QUERY".equals(resp.getRoute())) {
            return "SKIPPED：STRUCTURED_QUERY 使用完整结构化数据直接执行，不需要 " + stage;
        }
        if ("RULE".equals(resp.getRoute())) return "SKIPPED：规则快路径已直接得到结论";
        return "SKIPPED：当前 route=" + value(resp.getRoute()) + ", executionMode=" + value(resp.getExecutionMode());
    }

    private String planClass(EvidenceEvaluateRespVO resp) {
        if ("STRUCTURED_QUERY".equals(resp.getRoute())) return "STRUCTURED_QUERY";
        if ("RULE".equals(resp.getRoute())) return "RULE";
        if ("CLARIFY".equals(resp.getRoute())) return "CLARIFY";
        if ("ABSTAIN".equals(resp.getRoute())) return "ABSTAIN";
        return "SEMANTIC_QUERY";
    }

    private boolean looksLikeFilterQuery(String query) {
        return StrUtil.isNotBlank(query) && StrUtil.containsAny(query,
                "包含", "等于", "不等于", "大于", "小于", "至少", "至多", "其中", "标题", "申请人", "发明人");
    }

    private void ensureStageBefore(List<QueryStageTimingDTO> stages, String before, String stageName) {
        if (stages.stream().anyMatch(s -> s != null && stageName.equals(s.getStage()))) return;
        int index = -1;
        for (int i = 0; i < stages.size(); i++) {
            if (stages.get(i) != null && before.equals(stages.get(i).getStage())) {
                index = i;
                break;
            }
        }
        if (index < 0) return;
        QueryStageTimingDTO stage = new QueryStageTimingDTO();
        stage.setStage(stageName);
        stage.setStatus("SUCCEEDED");
        stage.setElapsedMs(0L);
        stage.setSkipped(false);
        stages.add(index, stage);
    }

    private void renumber(List<QueryStageTimingDTO> stages) {
        int seq = 0;
        for (QueryStageTimingDTO stage : stages) if (stage != null) stage.setSeq(++seq);
    }

    private String list(List<Long> values) {
        if (values == null || values.isEmpty()) return "[]";
        if (values.size() <= 10) return values.toString();
        return values.subList(0, 10) + "... total=" + values.size();
    }

    private String listString(List<String> values) {
        if (values == null || values.isEmpty()) return "[]";
        if (values.size() <= 6) return values.toString();
        return values.subList(0, 6) + "... total=" + values.size();
    }

    private String quote(String value) {
        return "“" + StrUtil.blankToDefault(value, "-") + "”";
    }

    private String value(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }

    private String abbreviate(String value, int max) {
        if (value == null) return "-";
        String clean = value.replace('\n', ' ').replace('\r', ' ').trim();
        return clean.length() <= max ? clean : clean.substring(0, max) + "...";
    }

    private String limit(String value) {
        if (value == null) return null;
        return value.length() <= SUMMARY_LIMIT ? value : value.substring(0, SUMMARY_LIMIT) + "...";
    }
}
