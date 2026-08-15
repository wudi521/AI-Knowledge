package cn.iocoder.yudao.module.model.api;

import cn.iocoder.yudao.module.model.api.ModelApi;
import org.springframework.stereotype.Service;

/**
 * 模型网关 对外 RPC 实现
 */
@Service
public class ModelApiImpl implements ModelApi {

    @Override
    public String chat(String model, String prompt) {
        return "";
    }

}
