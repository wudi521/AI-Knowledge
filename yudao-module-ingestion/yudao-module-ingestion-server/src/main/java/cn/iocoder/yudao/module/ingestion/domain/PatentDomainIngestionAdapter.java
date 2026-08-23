package cn.iocoder.yudao.module.ingestion.domain;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.ingestion.domain.patent.PatentClaimParser;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 专利领域入库适配器(PATENT): 著录信息提取(规则优先) + 专利切片(章节/权利要求完整)
 */
@Slf4j
@Component
public class PatentDomainIngestionAdapter implements DomainIngestionAdapter {

    private static final Pattern CLAIM_SECTION = Pattern.compile(
            "权\\s*利\\s*要\\s*求\\s*书(.*?)(?:说\\s*明\\s*书|摘\\s*要)",
            Pattern.DOTALL);

    private final PatentMetadataExtractor metadataExtractor = new PatentMetadataExtractor();
    private final PatentClaimParser claimParser = new PatentClaimParser();
    private final PatentSplitter splitter = new PatentSplitter();

    @Override
    public String domainCode() {
        return "PATENT";
    }

    @Override
    public String extractMetadata(ParsedDocument document, KnowledgeDocumentRespDTO source) {
        try {
            String plainText = document.toPlainText();
            PatentMetadata meta = metadataExtractor.extract(plainText);
            meta.setClaimCount(countClaims(plainText));
            meta.setExtractorVersion("patent-mvp-1.1");
            return JSONUtil.toJsonStr(meta);
        } catch (Exception e) {
            log.warn("[extractMetadata][专利元数据提取失败, 返回空: {}]", e.getMessage());
            return null;
        }
    }

    private int countClaims(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            return 0;
        }
        Matcher matcher = CLAIM_SECTION.matcher(plainText);
        if (!matcher.find()) {
            log.warn("[countClaims][未定位到权利要求书章节, claimCount=0]");
            return 0;
        }
        return claimParser.parse(matcher.group(1)).size();
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
