package cn.iocoder.yudao.module.knowledge.service.intent;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.intent.AiIntentDO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.AiKnowledgeBaseDO;
import cn.iocoder.yudao.module.knowledge.dal.mysql.knowledge.AiKnowledgeBaseMapper;
import cn.iocoder.yudao.module.knowledge.service.common.PublishedContentCollector;
import cn.iocoder.yudao.module.knowledge.service.prompt.PromptSupport;
import cn.iocoder.yudao.module.model.api.ModelApi;
import cn.iocoder.yudao.module.model.api.dto.ModelChatReqDTO;
import com.alibaba.ttl.TtlRunnable;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * 知识库意图总结器。当前自动总结属于 GENERAL 客服能力；PATENT 使用固定领域意图，不参与该流程。
 */
@Slf4j
@Component
public class IntentSummarizer {

    private static final ExecutorService ASYNC_EXECUTOR = new java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(100),
            r -> {
                Thread t = new Thread(r, "intent-summarizer");
                t.setDaemon(true);
                return t;
            },
            (r, executor) -> log.warn("[intent-summarizer][队列已满(100), 拒绝意图总结任务: {}]", r));

    private static final String SYSTEM_PROMPT = """
            你是知识库意图分析师。根据下方知识库已发布内容, 总结该知识库能回答的客户意图分类(2~8 个), 每个意图给简短说明(20字内)。
            只输出合法 JSON, 不要输出其他文字。格式: {"intents":[{"name":"保修","description":"保修期与免费维修政策"}]}
            要求:
            1. 意图名简短名词, 覆盖该库实际内容, 不要编造库中不存在的业务;
            2. 内容含价格/收费/计费/费用/合同条款时, 必须提炼出"收费/定价"或"合同条款"类意图;
            3. 内容为多类文档混合时, 意图应覆盖各类文档的客户问题。
            """;

    private static final int MAX_NAME_LEN = 64;
    private static final int MAX_DESC_LEN = 500;

    @Resource
    private IntentService intentService;
    @Resource
    private PublishedContentCollector publishedContentCollector;
    @Resource
    private ModelApi modelApi;
    @Resource
    private PromptSupport promptSupport;
    @Resource
    private AiKnowledgeBaseMapper aiKnowledgeBaseMapper;

    public int summarizeByKb(Long kbId) {
        try {
            AiKnowledgeBaseDO kb = aiKnowledgeBaseMapper.selectById(kbId);
            if (kb != null && "PATENT".equalsIgnoreCase(kb.getDomainCode())) {
                log.info("[summarizeByKb][知识库 {} 为 PATENT, 使用固定领域意图, 跳过客服自动意图总结]", kbId);
                return 0;
            }

            String content = publishedContentCollector.collectPublishedContent(kbId);
            if (StrUtil.isBlank(content)) {
                log.warn("[summarizeByKb][知识库 {} 无已发布内容, 跳过总结]", kbId);
                return 0;
            }

            ModelChatReqDTO req = new ModelChatReqDTO();
            req.setSystem(promptSupport.get("intent-summarize", SYSTEM_PROMPT));
            req.setUser(content);
            String resp = modelApi.chat(req).getCheckedData();
            if (StrUtil.isBlank(resp)) {
                log.warn("[summarizeByKb][知识库 {} LLM 返回为空, 跳过]", kbId);
                return -1;
            }
            List<AiIntentDO> intents = parseIntents(resp);
            if (CollUtil.isEmpty(intents)) {
                log.warn("[summarizeByKb][知识库 {} LLM 输出无可解析意图, 跳过; 原文: {}]", kbId, resp);
                return -1;
            }
            intentService.replaceAutoIntents(kbId, intents);
            log.info("[summarizeByKb][知识库 {} 意图总结完成: {} 个意图]", kbId, intents.size());
            return intents.size();
        } catch (Exception e) {
            log.warn("[summarizeByKb][知识库 {} 意图总结失败: {}]", kbId, e.getMessage(), e);
            return -1;
        }
    }

    public void summarizeByKbAsync(Long kbId) {
        Runnable task = () -> {
            try {
                summarizeByKb(kbId);
            } catch (Exception e) {
                log.warn("[summarizeByKbAsync][知识库 {} 意图总结异常: {}]", kbId, e.getMessage(), e);
            }
        };
        ASYNC_EXECUTOR.execute(TtlRunnable.get(task));
    }

    private List<AiIntentDO> parseIntents(String resp) {
        int start = resp.indexOf('{');
        int end = resp.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return List.of();
        }
        JSONObject json = JSONUtil.parseObj(resp.substring(start, end + 1));
        JSONArray arr = json.getJSONArray("intents");
        if (arr == null) {
            return List.of();
        }
        List<AiIntentDO> intents = new ArrayList<>();
        for (Object o : arr) {
            if (!(o instanceof JSONObject obj)) {
                continue;
            }
            String name = StrUtil.nullToEmpty(obj.getStr("name")).trim();
            if (StrUtil.isBlank(name)) {
                continue;
            }
            intents.add(AiIntentDO.builder()
                    .name(StrUtil.sub(name, 0, MAX_NAME_LEN))
                    .description(StrUtil.sub(StrUtil.nullToEmpty(obj.getStr("description")), 0, MAX_DESC_LEN))
                    .build());
        }
        return intents;
    }
}
