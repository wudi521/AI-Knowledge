package cn.iocoder.yudao.module.retrieval.service.search.scope;

import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import cn.iocoder.yudao.module.knowledge.api.dto.PatentDocumentLookupReqDTO;
import cn.iocoder.yudao.module.retrieval.service.domain.PatentQueryPreParser;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 专利领域 hard-scope：申请号/公布号只负责确定性定位文档，不污染通用 BM25/Vector。 */
@Component
public class PatentExactIdentifierScopePlugin implements RetrievalScopePlugin {

    private final PatentQueryPreParser preParser;
    private final KnowledgeApi knowledgeApi;

    public PatentExactIdentifierScopePlugin(PatentQueryPreParser preParser, KnowledgeApi knowledgeApi) {
        this.preParser = preParser;
        this.knowledgeApi = knowledgeApi;
    }

    @Override
    public String pluginId() {
        return "retrieval-scope:patent-exact-identifier";
    }

    @Override
    public Set<String> supportedDomains() {
        return Set.of("PATENT");
    }

    @Override
    public RetrievalScopeDecision refine(RetrievalScopeContext context) {
        PatentQueryPreParser.PatentQueryHints hints = preParser.parse(context.query());
        if (hints == null || !hints.hasExactDocumentIdentifier()) {
            return RetrievalScopeDecision.unchanged(pluginId(), context.documentIds());
        }
        try {
            PatentDocumentLookupReqDTO req = new PatentDocumentLookupReqDTO();
            req.setKbIds(context.kbIds());
            req.setApplicationNo(hints.getApplicationNo());
            req.setPublicationNo(hints.getPublicationNo());
            List<Long> resolved = knowledgeApi.lookupPatentDocuments(req).getCheckedData();
            resolved = resolved == null ? List.of() : resolved.stream().filter(java.util.Objects::nonNull).distinct().toList();
            if (resolved.isEmpty()) {
                return new RetrievalScopeDecision(pluginId(), List.of(), true, true, false,
                        "exact patent identifier resolved to no document");
            }
            List<Long> narrowed = intersect(context.documentIds(), resolved);
            if (narrowed.isEmpty()) {
                return new RetrievalScopeDecision(pluginId(), List.of(), true, true, false,
                        "exact patent identifier is outside current hard scope");
            }
            return new RetrievalScopeDecision(pluginId(), narrowed, true, false, false,
                    "resolved exact patent identifier to " + narrowed.size() + " document(s)");
        } catch (Exception e) {
            return new RetrievalScopeDecision(pluginId(), List.of(), true, true, true,
                    "patent identifier lookup failed: " + e.getClass().getSimpleName());
        }
    }

    private List<Long> intersect(List<Long> current, List<Long> resolved) {
        if (current == null || current.isEmpty()) return resolved;
        Set<Long> allowed = new LinkedHashSet<>(resolved);
        return current.stream().filter(allowed::contains).distinct().toList();
    }
}
