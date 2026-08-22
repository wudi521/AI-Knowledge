package cn.iocoder.yudao.module.retrieval.service.search;

import cn.iocoder.yudao.module.ingestion.api.IngestionApi;
import cn.iocoder.yudao.module.ingestion.api.dto.ChunkDocInfoDTO;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 结果过滤与补全: 知识库可见性 + chunk 已发布 + 内容/文档信息
 * <p>
 * 越权 0 容忍: 权限/状态过滤均为"剔除", 失败时保守返回空
 */
@Slf4j
@Service
public class ResultFilter {

    @Resource
    private KnowledgeApi knowledgeApi;
    @Resource
    private IngestionApi ingestionApi;

    /** 返回指定用户可见的知识库编号集合(失败返回空; RPC 无登录态, 用户显式传递) */
    public Set<Long> getVisibleKbIds(Long userId) {
        try {
            return knowledgeApi.getVisibleKbIds(userId).getCheckedData();
        } catch (Exception e) {
            log.warn("[getVisibleKbIds][获取可见知识库失败, 返回空: {}]", e.getMessage());
            return Set.of();
        }
    }

    /** 过滤出已发布的 chunkId 集合(输入全量, 返回 PUBLISHED 的; 失败保守返回空) */
    public Set<Long> filterPublished(Set<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return Set.of();
        }
        try {
            Map<Long, Boolean> map = ingestionApi.getChunkPublishMap(chunkIds.stream().toList()).getCheckedData();
            return chunkIds.stream().filter(id -> Boolean.TRUE.equals(map.get(id))).collect(Collectors.toSet());
        } catch (Exception e) {
            log.warn("[filterPublished][状态查询失败, 保守返回空: {}]", e.getMessage());
            return Set.of();
        }
    }

    /** 批量查询片段内容(缺失由调用方兜底空串; 失败返回空 Map) */
    public Map<Long, String> getChunkContents(Collection<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return Map.of();
        }
        try {
            return ingestionApi.getChunkContents(chunkIds.stream().toList()).getCheckedData();
        } catch (Exception e) {
            log.warn("[getChunkContents][内容查询失败, 返回空: {}]", e.getMessage());
            return Map.of();
        }
    }

    /** 批量查询片段所属文档信息(chunkId -> documentId/documentName/versionNo; 失败返回空 Map) */
    /** 批量查询 chunk → 父块编号(父子检索扩展; 失败返回空, 不阻断) */
    public Map<Long, Long> getChunkParents(Collection<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return Map.of();
        }
        try {
            return ingestionApi.getChunkParents(chunkIds.stream().toList()).getCheckedData();
        } catch (Exception e) {
            log.warn("[getChunkParents][父块查询失败, 返回空: {}]", e.getMessage());
            return Map.of();
        }
    }

    public Map<Long, ChunkDocInfoDTO> getChunkDocInfo(Collection<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return Map.of();
        }
        try {
            return ingestionApi.getChunkDocInfo(chunkIds.stream().toList()).getCheckedData();
        } catch (Exception e) {
            log.warn("[getChunkDocInfo][文档信息查询失败, 返回空: {}]", e.getMessage());
            return Map.of();
        }
    }

}
