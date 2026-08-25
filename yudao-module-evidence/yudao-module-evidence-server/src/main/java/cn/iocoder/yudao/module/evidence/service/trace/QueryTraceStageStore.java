package cn.iocoder.yudao.module.evidence.service.trace;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.evidence.dal.dataobject.evidence.QueryTraceStageDO;
import cn.iocoder.yudao.module.evidence.dal.mysql.evidence.QueryTraceStageMapper;
import cn.iocoder.yudao.module.retrieval.api.dto.QueryStageTimingDTO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Query/Agent stage 审计存储。写失败不阻断回答主链；读取用于 traceId 事后回放。 */
@Slf4j
@Component
public class QueryTraceStageStore {
    private static final int SUMMARY_LIMIT = 1000;
    private final QueryTraceStageMapper mapper;

    public QueryTraceStageStore(QueryTraceStageMapper mapper) {
        this.mapper = mapper;
    }

    public void replace(String traceId, List<QueryStageTimingDTO> stages) {
        if (StrUtil.isBlank(traceId)) return;
        try {
            mapper.delete(new LambdaQueryWrapper<QueryTraceStageDO>()
                    .eq(QueryTraceStageDO::getTraceId, traceId));
            if (stages == null) return;
            int fallbackSeq = 1;
            for (QueryStageTimingDTO stage : stages) {
                if (stage == null || StrUtil.isBlank(stage.getStage())) continue;
                QueryTraceStageDO row = QueryTraceStageDO.builder()
                        .traceId(traceId)
                        .seq(stage.getSeq() == null ? fallbackSeq : stage.getSeq())
                        .stage(StrUtil.maxLength(stage.getStage(), 96))
                        .status(StrUtil.maxLength(stage.getStatus(), 24))
                        .elapsedMs(stage.getElapsedMs())
                        .errorCode(StrUtil.maxLength(stage.getErrorCode(), 128))
                        .errorMessage(StrUtil.maxLength(stage.getErrorMessage(), 500))
                        .inputSummary(StrUtil.maxLength(stage.getInputSummary(), SUMMARY_LIMIT))
                        .outputSummary(StrUtil.maxLength(stage.getOutputSummary(), SUMMARY_LIMIT))
                        .build();
                mapper.insert(row);
                fallbackSeq++;
            }
        } catch (Exception e) {
            log.warn("[query-trace-stage][persist failed traceId={} error={}]", traceId, e.getMessage());
        }
    }

    public List<QueryStageTimingDTO> find(String traceId) {
        if (StrUtil.isBlank(traceId)) return List.of();
        try {
            List<QueryTraceStageDO> rows = mapper.selectList(new LambdaQueryWrapper<QueryTraceStageDO>()
                    .eq(QueryTraceStageDO::getTraceId, traceId)
                    .orderByAsc(QueryTraceStageDO::getSeq, QueryTraceStageDO::getId));
            List<QueryStageTimingDTO> result = new ArrayList<>();
            for (QueryTraceStageDO row : rows) result.add(toDto(row));
            return result;
        } catch (Exception e) {
            log.warn("[query-trace-stage][replay failed traceId={} error={}]", traceId, e.getMessage());
            return List.of();
        }
    }

    private QueryStageTimingDTO toDto(QueryTraceStageDO row) {
        QueryStageTimingDTO dto = new QueryStageTimingDTO();
        dto.setStage(row.getStage());
        dto.setSeq(row.getSeq());
        dto.setStatus(row.getStatus());
        dto.setElapsedMs(row.getElapsedMs());
        dto.setSkipped(false);
        dto.setErrorCode(row.getErrorCode());
        dto.setErrorMessage(row.getErrorMessage());
        dto.setInputSummary(row.getInputSummary());
        dto.setOutputSummary(row.getOutputSummary());
        return dto;
    }
}
