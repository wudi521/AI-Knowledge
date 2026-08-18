package cn.iocoder.yudao.module.knowledge.service.intent;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.ingestion.api.IngestionApi;
import cn.iocoder.yudao.module.ingestion.api.dto.ChunkRespDTO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.intent.AiIntentDO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.AiDocumentDO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.version.AiDocVersionDO;
import cn.iocoder.yudao.module.knowledge.dal.mysql.knowledge.AiDocumentMapper;
import cn.iocoder.yudao.module.knowledge.dal.mysql.version.AiDocVersionMapper;
import cn.iocoder.yudao.module.model.api.ModelApi;
import cn.iocoder.yudao.module.model.api.dto.ModelChatReqDTO;
import com.alibaba.ttl.TtlRunnable;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 知识库意图总结器(LLM): 拉取知识库已发布内容 -> LLM 总结客户意图 -> 覆盖式写入 LLM_AUTO 意图
 * <p>
 * 失败语义: 任何失败(Feign/LLM/解析/落库)只 log.warn 并返回 -1, 绝不抛出,
 * 保证发布流程/手动接口不被 LLM 故障拖垮; LLM_AUTO 由 replaceAutoIntents 覆盖重写, MANUAL 永远保留。
 */
@Slf4j
@Component
public class IntentSummarizer {

    /**
     * 意图总结专用线程池(守护线程, 不阻止 JVM 退出; 单线程避免并发重复总结同一知识库)
     * 任务包装 TtlRunnable, 传递发布/请求线程的租户上下文(TenantBaseDO 落库必需)
     */
    private static final ExecutorService ASYNC_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "intent-summarizer");
        t.setDaemon(true);
        return t;
    });

    private static final String SYSTEM_PROMPT = """
            你是知识库意图分析师。根据下方知识库已发布内容, 总结该知识库能回答的客户意图分类(2~8 个), 每个意图给简短说明(20字内)。
            只输出合法 JSON, 不要输出其他文字。格式: {"intents":[{"name":"保修","description":"保修期与免费维修政策"}]}
            要求: 意图名简短名词, 覆盖该库实际内容, 不要编造库中不存在的业务。
            """;

    /** 参与总结的片段上限 */
    private static final int MAX_CHUNKS = 20;

    /** 单个片段内容截断长度(字) */
    private static final int MAX_CHUNK_LEN = 200;

    /** 意图名字段上限(varchar(64)) */
    private static final int MAX_NAME_LEN = 64;

    /** 意图说明字段上限(varchar(500)) */
    private static final int MAX_DESC_LEN = 500;

    @Resource
    private IntentService intentService;
    @Resource
    private IngestionApi ingestionApi;
    @Resource
    private ModelApi modelApi;
    @Resource
    private AiDocumentMapper aiDocumentMapper;
    @Resource
    private AiDocVersionMapper aiDocVersionMapper;

    /**
     * 同步总结知识库意图(手动 summarize 与异步任务共用)
     *
     * @param kbId 知识库编号
     * @return 新生成的意图数; 无已发布内容返回 0; 任何失败返回 -1(绝不抛异常)
     */
    public int summarizeByKb(Long kbId) {
        try {
            // 1. 收集知识库已发布内容(≤20 片段 × 200 字)
            String content = collectPublishedContent(kbId);
            if (StrUtil.isBlank(content)) {
                log.warn("[summarizeByKb][知识库 {} 无已发布内容, 跳过总结]", kbId);
                return 0;
            }
            // 2. LLM 总结
            ModelChatReqDTO req = new ModelChatReqDTO();
            req.setSystem(SYSTEM_PROMPT);
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
            // 3. 覆盖写入 LLM_AUTO(事务内; MANUAL 保留)
            intentService.replaceAutoIntents(kbId, intents);
            log.info("[summarizeByKb][知识库 {} 意图总结完成: {} 个意图]", kbId, intents.size());
            return intents.size();
        } catch (Exception e) {
            // 任何失败不上抛, 保留旧意图; 可通过手动 summarize 重跑
            log.warn("[summarizeByKb][知识库 {} 意图总结失败: {}]", kbId, e.getMessage(), e);
            return -1;
        }
    }

    /**
     * 异步总结: 立即返回不阻塞调用方; 内部 try/catch 保证绝不抛出
     */
    public void summarizeByKbAsync(Long kbId) {
        Runnable task = () -> {
            try {
                summarizeByKb(kbId);
            } catch (Exception e) {
                log.warn("[summarizeByKbAsync][知识库 {} 意图总结异常: {}]", kbId, e.getMessage(), e);
            }
        };
        ASYNC_EXECUTOR.execute(TtlRunnable.get(task)); // 传递租户上下文
    }

    /**
     * 收集知识库已发布版本片段内容: 文档(kbId) -> 已发布版本 -> 片段, 截断到上限
     */
    private String collectPublishedContent(Long kbId) {
        List<AiDocumentDO> docs = aiDocumentMapper.selectListByKbId(kbId);
        if (CollUtil.isEmpty(docs)) {
            return "";
        }
        List<AiDocVersionDO> versions = aiDocVersionMapper.selectPublishedByDocIds(
                docs.stream().map(AiDocumentDO::getId).toList());
        if (CollUtil.isEmpty(versions)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int chunkCount = 0;
        for (AiDocVersionDO version : versions) {
            List<ChunkRespDTO> chunks = ingestionApi.getChunksByVersion(version.getId()).getCheckedData();
            if (CollUtil.isEmpty(chunks)) {
                continue;
            }
            for (ChunkRespDTO chunk : chunks) {
                if (chunkCount >= MAX_CHUNKS) {
                    return sb.toString();
                }
                sb.append(StrUtil.sub(StrUtil.nullToEmpty(chunk.getContent()), 0, MAX_CHUNK_LEN)).append("\n\n");
                chunkCount++;
            }
        }
        return sb.toString();
    }

    /**
     * 解析 LLM 输出 {"intents":[{"name","description"}]}; 容错截取首个 { 到末个 }
     */
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
                continue; // 模型输出非对象元素时跳过, 不中断整批
            }
            String name = StrUtil.nullToEmpty(obj.getStr("name")).trim();
            if (StrUtil.isBlank(name)) {
                continue;
            }
            AiIntentDO intent = AiIntentDO.builder()
                    .name(StrUtil.sub(name, 0, MAX_NAME_LEN))
                    .description(StrUtil.sub(StrUtil.nullToEmpty(obj.getStr("description")), 0, MAX_DESC_LEN))
                    .build();
            intents.add(intent);
        }
        return intents;
    }

}
