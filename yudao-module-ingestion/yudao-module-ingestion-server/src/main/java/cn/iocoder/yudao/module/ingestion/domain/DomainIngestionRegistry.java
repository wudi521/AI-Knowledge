package cn.iocoder.yudao.module.ingestion.domain;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * @deprecated 兼容旧调用点。领域选择已经统一收敛到 {@link DomainChunkingPipeline}。
 * 新代码不要再维护独立 Map/Registry 规则，避免切片、检索、验证三条链各自发明一套插件机制。
 */
@Deprecated
@Component
public class DomainIngestionRegistry {

    private final DomainChunkingPipeline pipeline;

    public DomainIngestionRegistry(List<DomainIngestionAdapter> adapterList) {
        this.pipeline = new DomainChunkingPipeline(adapterList);
    }

    /** 按领域代码选择插件；未知领域由 GENERAL(*) 插件兜底。 */
    public DomainIngestionAdapter get(String domainCode) {
        return pipeline.pluginFor(domainCode);
    }
}
