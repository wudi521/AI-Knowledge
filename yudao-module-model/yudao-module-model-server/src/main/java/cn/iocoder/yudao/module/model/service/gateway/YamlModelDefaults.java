package cn.iocoder.yudao.module.model.service.gateway;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * yaml 模型默认配置(网关兜底): 读取 yudao.model.{chat,embedding,rerank}.{base-url,model}
 * 存量部署未配置 ai_model_config 时回退到 yaml, 保证不破坏现有行为
 */
@Component
public class YamlModelDefaults {

    @Resource
    private Environment environment;

    /** 虚拟模型目标(yaml 兜底; modelId 为 null) */
    public record ModelTarget(String type, String baseUrl, String modelName) {
    }

    /**
     * 按类型取 yaml 默认(不存在时返回 null, 由调用方抛错)
     */
    public ModelTarget resolve(String type) {
        String baseUrl = environment.getProperty("yudao.model." + type + ".base-url");
        String modelName = environment.getProperty("yudao.model." + type + ".model");
        if (baseUrl == null || baseUrl.isBlank() || modelName == null || modelName.isBlank()) {
            return null;
        }
        return new ModelTarget(type, baseUrl, modelName);
    }

}
