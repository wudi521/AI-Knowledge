package cn.iocoder.yudao.module.model.api;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.model.api.dto.ModelChatReqDTO;
import cn.iocoder.yudao.module.model.api.dto.ModelEmbeddingReqDTO;
import cn.iocoder.yudao.module.model.api.dto.ModelRerankReqDTO;
import cn.iocoder.yudao.module.model.enums.ApiConstants;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * 模型网关 对外 RPC 接口(Feign)
 * 其他模块通过 Feign 调用本接口, 实现位于 model-server
 */
@FeignClient(name = ApiConstants.NAME)
public interface ModelApi {

    /**
     * 文本向量化(Embedding)
     *
     * @param texts 文本列表(批量)
     * @return 每个文本对应的 1024 维向量列表
     */
    @PostMapping(ApiConstants.PREFIX + "/embedding")
    CommonResult<List<List<Float>>> embedding(@RequestBody List<String> texts);

    /**
     * 文本向量化(带场景/追踪号, 走模型网关计量)
     *
     * @param req 文本列表 + 场景 + 追踪号
     * @return 每个文本对应的向量列表
     */
    @PostMapping(ApiConstants.PREFIX + "/embedding-meta")
    CommonResult<List<List<Float>>> embeddingMeta(@RequestBody ModelEmbeddingReqDTO req);

    /**
     * 文本对话(LLM 生成, 供审核条目抽取/冲突判定)
     *
     * @param req 系统提示 + 用户提示
     * @return 模型输出文本(JSON 由调用方解析)
     */
    @PostMapping(ApiConstants.PREFIX + "/chat")
    CommonResult<String> chat(@RequestBody ModelChatReqDTO req);

    /**
     * 文本重排(相关性打分, 供检索平台重排候选)
     *
     * @param req 查询 + 候选文本列表
     * @return 与 documents 一一对应的相关性分(越高越相关)
     */
    @PostMapping(ApiConstants.PREFIX + "/rerank")
    CommonResult<List<Float>> rerank(@RequestBody ModelRerankReqDTO req);

}
