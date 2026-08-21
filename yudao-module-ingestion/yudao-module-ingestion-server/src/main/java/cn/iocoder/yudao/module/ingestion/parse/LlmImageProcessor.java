package cn.iocoder.yudao.module.ingestion.parse;

import cn.iocoder.yudao.module.model.api.ModelApi;
import cn.iocoder.yudao.module.model.api.dto.ModelChatReqDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 图片理解默认实现: 调 model-server 的 image 类型视觉模型(OpenAI 兼容多模态)。
 * 路由由 ModelGateway 按请求是否带图片自动选择(image 类型), 无需调用方指定。
 */
@Slf4j
@Component
public class LlmImageProcessor implements ImageProcessor {

    private static final String SYSTEM_PROMPT = """
            你是文档图片理解助手。给定一张文档中的图片(可能附带所属上下文), 输出一段面向检索的中文描述, 要求:
            1. 描述图片核心内容: 主体/布局/图中文字(OCR)/数据要点/流程/关系;
            2. 突出可检索的关键信息: 实体名、编号、数值、条款、产品/部件名;
            3. 若有图中文字, 原样摘录关键文字(编号/名称/数值);
            4. 100~300 字, 只输出描述本身, 不要解释。
            """;

    @Resource
    private ModelApi modelApi;

    /** 探测结果缓存(30s 过期, 模型管理页启用/停用 image 模型后自动感知) */
    private volatile Boolean cachedEnabled;
    private volatile long cachedAt;

    @Override
    public String describeImage(String imageRef, String contextText) {
        try {
            ModelChatReqDTO req = new ModelChatReqDTO();
            req.setSystem(SYSTEM_PROMPT);
            req.setUser(contextText == null || contextText.isBlank()
                    ? "请描述这张图片" : "图片所属上下文:\n" + contextText);
            req.setImages(List.of(imageRef));
            req.setTemperature(0.2);
            String desc = modelApi.chat(req).getCheckedData();
            if (desc == null || desc.isBlank()) {
                log.warn("[describeImage][视觉模型返回空描述, 降级跳过]");
                return null;
            }
            return desc.trim();
        } catch (Exception e) {
            log.warn("[describeImage][图片理解失败, 降级跳过: {}]", e.getMessage());
            return null;
        }
    }

    @Override
    public boolean isEnabled() {
        long now = System.currentTimeMillis();
        if (cachedEnabled == null || now - cachedAt > 30_000) {
            boolean v = false;
            try {
                v = Boolean.TRUE.equals(modelApi.hasEnabled("image").getCheckedData());
            } catch (Exception e) {
                log.warn("[isEnabled][image 模型探测失败, 视为不可用: {}]", e.getMessage());
            }
            cachedEnabled = v;
            cachedAt = now;
        }
        return cachedEnabled;
    }
}
