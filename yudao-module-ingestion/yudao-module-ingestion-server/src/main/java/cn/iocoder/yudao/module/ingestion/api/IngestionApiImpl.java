package cn.iocoder.yudao.module.ingestion.api;

import cn.iocoder.yudao.module.ingestion.api.IngestionApi;
import org.springframework.stereotype.Service;

/**
 * 入库管线 对外 RPC 实现
 */
@Service
public class IngestionApiImpl implements IngestionApi {

    @Override
    public Boolean triggerIngest(Long documentId) {
    return true;

}
