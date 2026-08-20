package cn.iocoder.yudao.module.model.api;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.model.api.dto.ModelChatReqDTO;
import cn.iocoder.yudao.module.model.api.dto.ModelEmbeddingReqDTO;
import cn.iocoder.yudao.module.model.api.dto.ModelRerankReqDTO;
import cn.iocoder.yudao.module.model.service.gateway.ModelGateway;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 模型网关 对外 RPC 实现
 */
@RestController // 提供 RESTful API 接口，给 Feign 调用
@Validated
public class ModelApiImpl implements ModelApi {

    @Resource
    private ModelGateway modelGateway;

    @Override
    public CommonResult<List<List<Float>>> embedding(List<String> texts) {
        return success(modelGateway.embedding(texts, null, null));
    }

    @Override
    public CommonResult<List<List<Float>>> embeddingMeta(ModelEmbeddingReqDTO req) {
        return success(modelGateway.embedding(req.getTexts(), req.getScenario(), req.getTraceId()));
    }

    @Override
    public CommonResult<String> chat(ModelChatReqDTO req) {
        return success(modelGateway.chat(req, req.getScenario(), req.getTraceId()));
    }

    @Override
    public CommonResult<List<Float>> rerank(ModelRerankReqDTO req) {
        return success(modelGateway.rerank(req, req.getScenario(), req.getTraceId()));
    }

}
