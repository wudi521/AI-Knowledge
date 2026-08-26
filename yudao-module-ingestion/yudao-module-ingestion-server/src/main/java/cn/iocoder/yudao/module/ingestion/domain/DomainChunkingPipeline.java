package cn.iocoder.yudao.module.ingestion.domain;

import cn.iocoder.yudao.framework.common.plugin.DomainPluginContext;
import cn.iocoder.yudao.framework.common.plugin.DomainPluginResolver;
import cn.iocoder.yudao.module.ingestion.split.Chunk;
import cn.iocoder.yudao.module.ingestion.split.ParsedDocument;
import cn.iocoder.yudao.module.ingestion.split.SplitParams;
import cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeDocumentRespDTO;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 通用切片 Pipeline。
 *
 * <p>Pipeline 不知道专利、合同、法规等具体规则，只负责：构造领域上下文 -> 选择插件 ->
 * 执行领域元数据提取 -> 执行领域切片。插件发现和优先级由框架级 DomainPluginResolver 统一负责。</p>
 */
@Component
public class DomainChunkingPipeline {

    private final DomainPluginResolver<DomainIngestionAdapter> resolver;

    public DomainChunkingPipeline(List<DomainIngestionAdapter> plugins) {
        this.resolver = new DomainPluginResolver<>(plugins);
    }

    /**
     * 完整执行切片领域阶段。领域元数据的持久化仍由入库事务编排层负责，避免插件越权写库。
     */
    public Result execute(ParsedDocument document, SplitParams params, KnowledgeDocumentRespDTO source) {
        DomainIngestionAdapter plugin = resolve(source == null ? null : source.getDomainCode(), source);
        String metadata = plugin.extractMetadata(document, source);
        List<Chunk> chunks = plugin.split(document, params, metadata);
        return new Result(plugin.pluginId(), plugin.domainCode(), metadata,
                chunks == null ? List.of() : List.copyOf(chunks));
    }

    /**
     * 迁移兼容入口：旧 IngestService 仍可先拿到领域插件，后续会逐步收敛为 execute(...)。
     */
    public DomainIngestionAdapter pluginFor(String domainCode) {
        return resolve(domainCode, null);
    }

    public List<DomainIngestionAdapter> pluginsFor(String domainCode) {
        return resolver.resolve(context(domainCode, null));
    }

    private DomainIngestionAdapter resolve(String domainCode, KnowledgeDocumentRespDTO source) {
        return resolver.requireFirst(context(domainCode, source), "chunking");
    }

    private DomainPluginContext context(String domainCode, KnowledgeDocumentRespDTO source) {
        Long tenantId = source == null ? null : source.getTenantId();
        Long kbId = source == null ? null : source.getKbId();
        Map<String, Object> attributes = source == null || source.getType() == null
                ? Map.of() : Map.of("documentType", source.getType());
        return new DomainPluginContext(tenantId, kbId, domainCode,
                Set.of("CHUNKING", "DOMAIN_METADATA"), attributes);
    }

    public record Result(String pluginId,
                         String domainCode,
                         String domainMetadata,
                         List<Chunk> chunks) {
    }
}
