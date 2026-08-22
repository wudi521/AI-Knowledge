package cn.iocoder.yudao.module.ingestion.domain;

import cn.iocoder.yudao.module.ingestion.split.Chunk;
import cn.iocoder.yudao.module.ingestion.split.ParsedDocument;
import cn.iocoder.yudao.module.ingestion.split.SplitParams;
import cn.iocoder.yudao.module.ingestion.split.SplitterFactory;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 通用领域适配器(GENERAL): 元数据为空, 切分走通用 SplitterFactory(auto 策略)
 */
@Component
public class GeneralDomainIngestionAdapter implements DomainIngestionAdapter {

    @Resource
    private SplitterFactory splitterFactory;

    @Override
    public String domainCode() {
        return "GENERAL";
    }

    @Override
    public String extractMetadata(ParsedDocument document, cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeDocumentRespDTO source) {
        return null; // GENERAL 无领域元数据
    }

    @Override
    public List<Chunk> split(ParsedDocument document, SplitParams params, String domainMetadata) {
        return splitterFactory.getSplitter("auto").split(document, params);
    }
}
