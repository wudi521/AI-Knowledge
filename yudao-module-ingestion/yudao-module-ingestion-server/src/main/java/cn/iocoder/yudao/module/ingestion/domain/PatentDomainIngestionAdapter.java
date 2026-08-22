package cn.iocoder.yudao.module.ingestion.domain;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.ingestion.domain.patent.PatentMetadata;
import cn.iocoder.yudao.module.ingestion.domain.patent.PatentMetadataExtractor;
import cn.iocoder.yudao.module.ingestion.domain.patent.PatentSplitter;
import cn.iocoder.yudao.module.ingestion.split.Chunk;
import cn.iocoder.yudao.module.ingestion.split.ParsedDocument;
import cn.iocoder.yudao.module.ingestion.split.SplitParams;
import cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeDocumentRespDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 专利领域入库适配器(PATENT): 著录信息提取(规则优先) + 专利切片(章节/权利要求完整)
 */
@Slf4j
@Component
public class PatentDomainIngestionAdapter implements DomainIngestionAdapter {

    private final PatentMetadataExtractor metadataExtractor = new PatentMetadataExtractor();
    private final PatentSplitter splitter = new PatentSplitter();

    @Override
    public String domainCode() {
        return "PATENT";
    }

    @Override
    public String extractMetadata(ParsedDocument document, KnowledgeDocumentRespDTO source) {
        try {
            PatentMetadata meta = metadataExtractor.extract(document.toPlainText());
            // 权利要求数量由切分阶段回填(切分后更新)
            return JSONUtil.toJsonStr(meta);
        } catch (Exception e) {
            log.warn("[extractMetadata][专利元数据提取失败, 返回空: {}]", e.getMessage());
            return null;
        }
    }

    @Override
    public List<Chunk> split(ParsedDocument document, SplitParams params, String domainMetadata) {
        PatentMetadata meta = new PatentMetadata();
        if (domainMetadata != null) {
            try {
                meta = JSONUtil.toBean(domainMetadata, PatentMetadata.class);
            } catch (Exception e) {
                log.warn("[split][专利元数据解析失败, 用空著录: {}]", e.getMessage());
            }
        }
        return splitter.split(document, params, meta);
    }
}
