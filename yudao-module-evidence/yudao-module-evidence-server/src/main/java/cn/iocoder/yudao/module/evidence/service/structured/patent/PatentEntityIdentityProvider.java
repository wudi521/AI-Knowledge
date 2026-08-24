package cn.iocoder.yudao.module.evidence.service.structured.patent;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.evidence.domain.Evidence;
import cn.iocoder.yudao.module.evidence.service.semantics.DomainEntityIdentityProvider;
import org.springframework.stereotype.Component;

/** Patent Domain Pack 的业务实体身份：申请号优先，公布号兜底。 */
@Component
public class PatentEntityIdentityProvider implements DomainEntityIdentityProvider {

    @Override
    public String domainCode() {
        return "PATENT";
    }

    @Override
    public String identityKey(Evidence evidence, Long documentId) {
        if (evidence == null || StrUtil.isBlank(evidence.getChunkMetadata())) return null;
        try {
            var meta = JSONUtil.parseObj(evidence.getChunkMetadata());
            String applicationNo = normalize(meta.getStr("applicationNo"));
            if (StrUtil.isNotBlank(applicationNo)) return "PATENT:APP:" + applicationNo;
            String publicationNo = normalize(meta.getStr("publicationNo"));
            if (StrUtil.isNotBlank(publicationNo)) return "PATENT:PUB:" + publicationNo;
        } catch (Exception ignored) {
            // metadata 不可解析时由 Core 回退 documentId。
        }
        return null;
    }

    private String normalize(String value) {
        return value == null ? null : value.replaceAll("\\s+", "").toUpperCase();
    }
}
