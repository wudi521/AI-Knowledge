package cn.iocoder.yudao.module.retrieval.api;

import cn.iocoder.yudao.module.retrieval.api.RetrievalApi;
import org.springframework.stereotype.Service;

/**
 * 检索平台 对外 RPC 实现
 */
@Service
public class RetrievalApiImpl implements RetrievalApi {

    @Override
    public java.util.List<String> search(Long tenantId, String query) {
        return java.util.Collections.emptyList();
    }

}
