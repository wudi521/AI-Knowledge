package cn.iocoder.yudao.module.evidence.api;

import cn.iocoder.yudao.module.evidence.api.EvidenceApi;
import org.springframework.stereotype.Service;

/**
 * 证据平台 对外 RPC 实现
 */
@Service
public class EvidenceApiImpl implements EvidenceApi {

    @Override
    public String verifyClaim(String claim, java.util.List<String> chunkIds) {
    return "UNSUPPORTED";

}
