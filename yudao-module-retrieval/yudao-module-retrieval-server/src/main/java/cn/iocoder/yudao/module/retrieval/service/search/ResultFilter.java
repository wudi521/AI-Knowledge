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
 * 越权 0 容忍: 权限/状态过滤均为"剔除", 失败时保守返回空。
 */
@Slf4j
@Service
public class ResultFilter {

    @Resource private KnowledgeApi knowledgeApi;
    @Resource private IngestionApi ingestionApi;

    public Set<Long> getVisibleKbIds(Long userId) {
        try {
            return knowledgeApi.getVisibleKbIds(userId).getCheckedData();
        } catch (Exception e) {
            log.warn("[getVisibleKbIds][获取可见知识库失败, 返回空: {}]", e.getMessage());
            return Set.of();
        }
    }

    public Set<Long> filterPublished(Set<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) return Set.of();
        try {
            Map<Long, Boolean> map = ingestionApi.getChunkPublishMap(chunkIds.stream().toList()).getCheckedData();
            return chunkIds.stream().filter(id -> Boolean.TRUE.equals(map.get(id))).collect(Collectors.toSet());
        } catch (Exception e) {
            log.warn("[filterPublished][状态查询失败, 保守返回空: {}]", e.getMessage());
            return Set.of();
        }
    }

    public Map<Long, String> getChunkContents(Collection<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) return Map.of();
        try {
            return ingestionApi.getChunkContents(chunkIds.stream().toList()).getCheckedData();
        } catch (Exception e) {
            log.warn("[getChunkContents][内容查询失败, 返回空: {}]", e.getMessage());
            return Map.of();
        }
    }

    public Map<Long, String> getChunkMetadatas(Collection<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) return Map.of();
        try {
            return ingestionApi.getChunkMetadatas(chunkIds.stream().toList()).getCheckedData();
        } catch (Exception e) {
            log.warn("[getChunkMetadatas][元数据查询失败, 返回空: {}]", e.getMessage());
            return Map.of();
        }
    }

    public Map<Long, Long> getChunkParents(Collection<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) return Map.of();
        try {
            return ingestionApi.getChunkParents(chunkIds.stream().toList()).getCheckedData();
        } catch (Exception e) {
            log.warn("[getChunkParents][父块查询失败, 返回空: {}]", e.getMessage());
            return Map.of();
        }
    }

    public Map<Long, ChunkDocInfoDTO> getChunkDocInfo(Collection<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) return Map.of();
        try {
            return ingestionApi.getChunkDocInfo(chunkIds.stream().toList()).getCheckedData();
        } catch (Exception e) {
            log.warn("[getChunkDocInfo][文档信息查询失败, 返回空: {}]", e.getMessage());
            return Map.of();
        }
    }

    /**
     * EXACT_CLAIM: 在已由 KnowledgeApi 精确解析出的 documentIds 内，用 MySQL chunk metadata 定位权利要求。
     * <p>
     * 不依赖 ES/Milvus，不做语义匹配。只接受 PUBLISHED + PATENT_CLAIM + metadata.claimNo 精确相等。
     * 任一 RPC 失败按 fail-closed 返回空，避免退化成跨专利检索。
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

}
