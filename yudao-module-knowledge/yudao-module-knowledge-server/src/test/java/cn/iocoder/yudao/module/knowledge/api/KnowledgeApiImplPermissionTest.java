package cn.iocoder.yudao.module.knowledge.api;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.ingestion.api.IngestionApi;
import cn.iocoder.yudao.module.ingestion.api.dto.ChunkDocInfoDTO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.AiDocumentDO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.AiKnowledgeBaseDO;
import cn.iocoder.yudao.module.knowledge.dal.mysql.knowledge.AiDocumentMapper;
import cn.iocoder.yudao.module.knowledge.dal.mysql.knowledge.AiKnowledgeBaseMapper;
import cn.iocoder.yudao.module.knowledge.service.knowledge.KnowledgePermissionHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeApiImplPermissionTest {

    @Mock IngestionApi ingestionApi;
    @Mock AiDocumentMapper aiDocumentMapper;
    @Mock AiKnowledgeBaseMapper aiKnowledgeBaseMapper;
    @Mock KnowledgePermissionHelper knowledgePermissionHelper;

    @InjectMocks KnowledgeApiImpl api;

    @Test
    void nullUserOrChunkAlwaysFailsClosed() {
        assertThat(api.checkKnowledgePermission(null, 9L)).isFalse();
        assertThat(api.checkKnowledgePermission(101L, null)).isFalse();
    }

    @Test
    void missingChunkMappingFailsClosed() {
        when(ingestionApi.getChunkDocInfo(List.of(101L)))
                .thenReturn(CommonResult.success(Map.of()));
        assertThat(api.checkKnowledgePermission(101L, 9L)).isFalse();
    }

    @Test
    void visibleKbAllowsChunk() {
        ChunkDocInfoDTO info = new ChunkDocInfoDTO();
        info.setChunkId(101L);
        info.setDocumentId(67L);
        when(ingestionApi.getChunkDocInfo(List.of(101L)))
                .thenReturn(CommonResult.success(Map.of(101L, info)));

        AiDocumentDO doc = new AiDocumentDO();
        doc.setId(67L);
        doc.setKbId(6L);
        when(aiDocumentMapper.selectById(67L)).thenReturn(doc);

        AiKnowledgeBaseDO kb = new AiKnowledgeBaseDO();
        kb.setId(6L);
        when(aiKnowledgeBaseMapper.selectById(6L)).thenReturn(kb);
        when(knowledgePermissionHelper.isKbVisibleToUser(9L, kb)).thenReturn(true);

        assertThat(api.checkKnowledgePermission(101L, 9L)).isTrue();
    }

    @Test
    void deniedKbRejectsChunk() {
        ChunkDocInfoDTO info = new ChunkDocInfoDTO();
        info.setChunkId(101L);
        info.setDocumentId(67L);
        when(ingestionApi.getChunkDocInfo(List.of(101L)))
                .thenReturn(CommonResult.success(Map.of(101L, info)));

        AiDocumentDO doc = new AiDocumentDO();
        doc.setId(67L);
        doc.setKbId(6L);
        when(aiDocumentMapper.selectById(67L)).thenReturn(doc);

        AiKnowledgeBaseDO kb = new AiKnowledgeBaseDO();
        kb.setId(6L);
        when(aiKnowledgeBaseMapper.selectById(6L)).thenReturn(kb);
        when(knowledgePermissionHelper.isKbVisibleToUser(9L, kb)).thenReturn(false);

        assertThat(api.checkKnowledgePermission(101L, 9L)).isFalse();
    }

    @Test
    void dependencyFailureFailsClosed() {
        when(ingestionApi.getChunkDocInfo(List.of(101L))).thenThrow(new RuntimeException("rpc down"));
        assertThat(api.checkKnowledgePermission(101L, 9L)).isFalse();
    }
}
