package cn.iocoder.yudao.module.retrieval.service.search;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.ingestion.api.IngestionApi;
import cn.iocoder.yudao.module.ingestion.api.dto.ChunkDocInfoDTO;
import cn.iocoder.yudao.module.ingestion.api.dto.ChunkRespDTO;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 结果过滤与补全: 知识库可见性 + chunk 已发布 + 内容/文档信息。
 *
 * <p>Legacy 方法保持“异常返回空”的历史兼容；新的 Planned/Exact Pipeline 必须使用带状态的
 * `*Result` 方法，显式区分正常空集合与上游读取失败。</p>
 */
@Slf4j
@Service
public class ResultFilter {

    @Resource private KnowledgeApi knowledgeApi;
    @Resource private IngestionApi ingestionApi;

    /** 统一外部读取合同：data 可为空集合，但 failed=true 绝不能被解释为业务零结果。 */
    public record ReadResult<T>(T data, boolean failed, String errorMessage) {
        public static <T> ReadResult<T> success(T data) {
            return new ReadResult<>(data, false, null);
        }
        public static <T> ReadResult<T> failure(T safeData, String errorMessage) {
            return new ReadResult<>(safeData, true, errorMessage);
        }
    }

    public ReadResult<Set<Long>> getVisibleKbIdsResult(Long userId) {
        try {
            Set<Long> data = knowledgeApi.getVisibleKbIds(userId).getCheckedData();
            if (data == null) return ReadResult.failure(Set.of(), "knowledge visibility returned null");
            return ReadResult.success(Set.copyOf(data));
        } catch (Exception e) {
            log.warn("[getVisibleKbIdsResult][获取可见知识库失败: {}]", e.getMessage());
            return ReadResult.failure(Set.of(), failureMessage(e));
        }
    }

    /** Legacy 兼容：旧链仍按异常空集合处理。 */
    public Set<Long> getVisibleKbIds(Long userId) {
        return getVisibleKbIdsResult(userId).data();
    }

    public ReadResult<Set<Long>> filterPublishedResult(Set<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) return ReadResult.success(Set.of());
        try {
            Map<Long, Boolean> map = ingestionApi.getChunkPublishMap(chunkIds.stream().toList()).getCheckedData();
            if (map == null) return ReadResult.failure(Set.of(), "chunk publish map returned null");
            Set<Long> published = chunkIds.stream().filter(id -> Boolean.TRUE.equals(map.get(id))).collect(Collectors.toSet());
            return ReadResult.success(Set.copyOf(published));
        } catch (Exception e) {
            log.warn("[filterPublishedResult][状态查询失败: {}]", e.getMessage());
            return ReadResult.failure(Set.of(), failureMessage(e));
        }
    }

    /** Legacy 兼容。 */
    public Set<Long> filterPublished(Set<Long> chunkIds) {
        return filterPublishedResult(chunkIds).data();
    }

    public ReadResult<Map<Long, String>> getChunkContentsResult(Collection<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) return ReadResult.success(Map.of());
        try {
            Map<Long, String> data = ingestionApi.getChunkContents(chunkIds.stream().toList()).getCheckedData();
            if (data == null) return ReadResult.failure(Map.of(), "chunk contents returned null");
            return ReadResult.success(Map.copyOf(data));
        } catch (Exception e) {
            log.warn("[getChunkContentsResult][内容查询失败: {}]", e.getMessage());
            return ReadResult.failure(Map.of(), failureMessage(e));
        }
    }

    /** Legacy 兼容。 */
    public Map<Long, String> getChunkContents(Collection<Long> chunkIds) {
        return getChunkContentsResult(chunkIds).data();
    }

    public ReadResult<Map<Long, String>> getChunkMetadatasResult(Collection<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) return ReadResult.success(Map.of());
        try {
            Map<Long, String> data = ingestionApi.getChunkMetadatas(chunkIds.stream().toList()).getCheckedData();
            if (data == null) return ReadResult.failure(Map.of(), "chunk metadata returned null");
            return ReadResult.success(Map.copyOf(data));
        } catch (Exception e) {
            log.warn("[getChunkMetadatasResult][元数据查询失败: {}]", e.getMessage());
            return ReadResult.failure(Map.of(), failureMessage(e));
        }
    }

    /** Legacy 兼容。 */
    public Map<Long, String> getChunkMetadatas(Collection<Long> chunkIds) {
        return getChunkMetadatasResult(chunkIds).data();
    }

    public ReadResult<Map<Long, ChunkDocInfoDTO>> getChunkDocInfoResult(Collection<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) return ReadResult.success(Map.of());
        try {
            Map<Long, ChunkDocInfoDTO> data = ingestionApi.getChunkDocInfo(chunkIds.stream().toList()).getCheckedData();
            if (data == null) return ReadResult.failure(Map.of(), "chunk document info returned null");
            return ReadResult.success(Map.copyOf(data));
        } catch (Exception e) {
            log.warn("[getChunkDocInfoResult][文档信息查询失败: {}]", e.getMessage());
            return ReadResult.failure(Map.of(), failureMessage(e));
        }
    }

    /** Legacy 兼容。 */
    public Map<Long, ChunkDocInfoDTO> getChunkDocInfo(Collection<Long> chunkIds) {
        return getChunkDocInfoResult(chunkIds).data();
    }

    public Map<Long, Long> getChunkParents(Collection<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) return Map.of();
        try {
            Map<Long, Long> data = ingestionApi.getChunkParents(chunkIds.stream().toList()).getCheckedData();
            return data == null ? Map.of() : data;
        } catch (Exception e) {
            log.warn("[getChunkParents][父块查询失败, 返回空: {}]", e.getMessage());
            return Map.of();
        }
    }

    /**
     * P0-10: 按文档批量返回已发布 chunk(不含 claimNo 过滤; 供 Legacy EXACT_METADATA 多轮继承定位 anchor)
     */
    public List<ChunkRespDTO> findPublishedChunksByDocuments(List<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) return List.of();
        List<ChunkRespDTO> matches = new ArrayList<>();
        try {
            for (Long documentId : documentIds.stream().distinct().toList()) {
                List<Long> versionIds = knowledgeApi.getDocVersionIds(documentId).getCheckedData();
                if (versionIds == null || versionIds.isEmpty()) continue;
                for (Long versionId : versionIds) {
                    List<ChunkRespDTO> chunks = ingestionApi.getChunksByVersion(versionId).getCheckedData();
                    if (chunks == null) continue;
                    for (ChunkRespDTO chunk : chunks) {
                        if (chunk != null && "PUBLISHED".equals(chunk.getStatus())) {
                            matches.add(chunk);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[findPublishedChunksByDocuments][文档 chunk 定位失败, fail-closed: {}]", e.getMessage());
            return List.of();
        }
        return matches.stream().collect(Collectors.toMap(
                ChunkRespDTO::getId, c -> c, (a, b) -> a, java.util.LinkedHashMap::new))
                .values().stream().toList();
    }

    /**
     * Legacy EXACT_CLAIM: 在已由 KnowledgeApi 精确解析出的 documentIds 内，用 MySQL chunk metadata 定位权利要求。
     */
    public List<ChunkRespDTO> findPublishedPatentClaimChunks(List<Long> documentIds, Integer claimNo) {
        if (documentIds == null || documentIds.isEmpty() || claimNo == null || claimNo <= 0) return List.of();
        List<ChunkRespDTO> matches = new ArrayList<>();
        try {
            for (Long documentId : documentIds.stream().distinct().toList()) {
                List<Long> versionIds = knowledgeApi.getDocVersionIds(documentId).getCheckedData();
                if (versionIds == null || versionIds.isEmpty()) continue;
                for (Long versionId : versionIds) {
                    List<ChunkRespDTO> chunks = ingestionApi.getChunksByVersion(versionId).getCheckedData();
                    if (chunks == null) continue;
                    for (ChunkRespDTO chunk : chunks) {
                        if (chunk == null || !"PUBLISHED".equals(chunk.getStatus())
                                || !"PATENT_CLAIM".equals(chunk.getChunkType())
                                || StrUtil.isBlank(chunk.getMetadata())) continue;
                        try {
                            Integer currentClaimNo = JSONUtil.parseObj(chunk.getMetadata()).getInt("claimNo");
                            if (claimNo.equals(currentClaimNo)) matches.add(chunk);
                        } catch (Exception ignore) {
                            // 单个历史脏 metadata 不影响其它候选。
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[findPublishedPatentClaimChunks][精确 claim 定位失败, fail-closed: {}]", e.getMessage());
            return List.of();
        }
        return matches.stream().collect(Collectors.toMap(
                ChunkRespDTO::getId, c -> c, (a, b) -> a, java.util.LinkedHashMap::new))
                .values().stream().toList();
    }

    private String failureMessage(Exception e) {
        if (e == null) return "unknown upstream read failure";
        String message = e.getMessage();
        String safe = message == null ? "" : (message.length() <= 300 ? message : message.substring(0, 300));
        return e.getClass().getSimpleName() + (safe.isBlank() ? "" : ": " + safe);
    }
}
