package cn.iocoder.yudao.module.knowledge.api;

import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import org.springframework.stereotype.Service;

/**
 * 知识平台 对外 RPC 实现
 */
@Service
public class KnowledgeApiImpl implements KnowledgeApi {

    @Override
    public Boolean checkKnowledgePermission(Long chunkId, Long userId) {
        return true;
    }

}
