package cn.iocoder.yudao.module.knowledge.service.slot;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.AiKnowledgeBaseSlotDO;
import cn.iocoder.yudao.module.knowledge.service.common.PublishedContentCollector;
import cn.iocoder.yudao.module.knowledge.service.knowledge.AiKnowledgeBaseSlotService;
import cn.iocoder.yudao.module.model.api.ModelApi;
import cn.iocoder.yudao.module.model.api.dto.ModelChatReqDTO;
import com.alibaba.ttl.TtlRunnable;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 知识库槽位总结器(LLM): 拉取知识库已发布内容 -> LLM 总结条件维度(槽位) -> 覆盖式写入 LLM_AUTO 槽位
 * <p>
 * 失败语义: 任何失败(Feign/LLM/解析/落库)只 log.warn 并返回 -1, 绝不抛出;
 * LLM_AUTO 由 replaceAutoSlots 覆盖重写, MANUAL(用户编辑过)永远保留。
 */
@Slf4j
@Component
public class SlotSummarizer {

    /** 槽位总结专用线程池(守护线程; 单线程避免并发重复总结同一知识库) */
    private static final ExecutorService ASYNC_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "slot-summarizer");
        t.setDaemon(true);
        return t;
    });

    private static final String SYSTEM_PROMPT = """
            你是知识库条款分析师。根据下方知识库已发布内容, 总结该库条款/文档围绕的条件维度(槽位), 每个槽位含编码/名称/抽取说明/是否必填/排序。
            只输出合法 JSON, 不要输出其他文字。格式: {"slots":[{"code":"brand","name":"品牌型号","description":"客户产品的具体品牌/型号, 如 苹果13、X100 Pro; 仅说\\"手机/电脑/设备\\"这类泛指时视为未提供","required":true,"sort":1}]}
            要求:
            1. code 用英文蛇形且唯一(如 brand/faultType/purchaseTime/orderType/region/status);
            2. 只总结该库实际区分的条件维度, 不要编造库中不存在的业务; 2~8 个;
            3. required: 缺了该信息是否影响准确回答该库的问题(影响=必填 true, 否则 false);
            4. sort 按重要度 1..N 升序且不重复(组反问句顺序);
            5. description 需包含判定标准与示例(供槽位检测抽取器使用), 说明口语/泛指如何处理。
            """;

    /** 槽位编码/名/说明字段上限(对齐表 varchar(64)/varchar(500)) */
    private static final int MAX_CODE_NAME_LEN = 64;
    private static final int MAX_DESC_LEN = 500;

    @Resource
    private AiKnowledgeBaseSlotService slotService;
    @Resource
    private PublishedContentCollector publishedContentCollector;
    @Resource
    private ModelApi modelApi;

    /**
     * 同步总结知识库槽位(手动 summarize 与异步任务共用)
     *
     * @param kbId 知识库编号
     * @return 新生成的槽位数; 无已发布内容返回 0; 任何失败返回 -1(绝不抛异常)
     */
    public int summarizeByKb(Long kbId) {
        try {
            // 1. 收集知识库已发布内容
            String content = publishedContentCollector.collectPublishedContent(kbId);
            if (StrUtil.isBlank(content)) {
                log.warn("[summarizeByKb][知识库 {} 无已发布内容, 跳过总结]", kbId);
                return 0;
            }
            // 2. LLM 总结
            ModelChatReqDTO req = new ModelChatReqDTO();
            req.setSystem(SYSTEM_PROMPT);
            req.setUser(content);
            req.setTemperature(0.0); // 与槽位检测一致: 结构化输出确定性
            String resp = modelApi.chat(req).getCheckedData();
            if (StrUtil.isBlank(resp)) {
                log.warn("[summarizeByKb][知识库 {} LLM 返回为空, 跳过]", kbId);
                return -1;
            }
            List<AiKnowledgeBaseSlotDO> slots = parseSlots(resp);
            if (CollUtil.isEmpty(slots)) {
                log.warn("[summarizeByKb][知识库 {} LLM 输出无可解析槽位, 跳过; 原文: {}]", kbId, resp);
                return -1;
            }
            // 3. 覆盖写入 LLM_AUTO(事务内; MANUAL 保留); 返回值 = 实际插入数(与 MANUAL 同码被跳过)
            int inserted = slotService.replaceAutoSlots(kbId, slots);
            log.info("[summarizeByKb][知识库 {} 槽位总结完成: LLM 提议 {} 个, 实际插入 {} 个]", kbId, slots.size(), inserted);
            return inserted;
        } catch (Exception e) {
            log.warn("[summarizeByKb][知识库 {} 槽位总结失败: {}]", kbId, e.getMessage(), e);
            return -1;
        }
    }

    /** 异步总结: 立即返回不阻塞调用方; 内部 try/catch 保证绝不抛出 */
    public void summarizeByKbAsync(Long kbId) {
        Runnable task = () -> {
            try {
                summarizeByKb(kbId);
            } catch (Exception e) {
                log.warn("[summarizeByKbAsync][知识库 {} 槽位总结异常: {}]", kbId, e.getMessage(), e);
            }
        };
        ASYNC_EXECUTOR.execute(TtlRunnable.get(task)); // 传递租户上下文
    }

    /** 解析 LLM 输出 {"slots":[{code,name,description,required,sort}]}; 容错截取首个 { 到末个 } */
    private List<AiKnowledgeBaseSlotDO> parseSlots(String resp) {
        int start = resp.indexOf('{');
        int end = resp.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return List.of();
        }
        JSONObject json = JSONUtil.parseObj(resp.substring(start, end + 1));
        JSONArray arr = json.getJSONArray("slots");
        if (arr == null) {
            return List.of();
        }
        List<AiKnowledgeBaseSlotDO> slots = new ArrayList<>();
        Set<String> seenCodes = new LinkedHashSet<>();
        for (Object o : arr) {
            if (!(o instanceof JSONObject obj)) {
                continue;
            }
            String code = StrUtil.nullToEmpty(obj.getStr("code")).trim();
            String name = StrUtil.nullToEmpty(obj.getStr("name")).trim();
            if (StrUtil.isBlank(code) || StrUtil.isBlank(name)) {
                continue;
            }
            String slotCode = StrUtil.sub(code, 0, MAX_CODE_NAME_LEN);
            if (!seenCodes.add(slotCode)) {
                continue; // 同批重复编码去重(首个优先), 避免 uk(kb_id,slot_code,deleted) 冲突导致整批回滚
            }
            AiKnowledgeBaseSlotDO slot = AiKnowledgeBaseSlotDO.builder()
                    .slotCode(slotCode)
                    .slotName(StrUtil.sub(name, 0, MAX_CODE_NAME_LEN))
                    .description(StrUtil.sub(StrUtil.nullToEmpty(obj.getStr("description")), 0, MAX_DESC_LEN))
                    .required(Boolean.TRUE.equals(obj.getBool("required")))
                    .sort(obj.getInt("sort", 0))
                    .build();
            slots.add(slot);
        }
        return slots;
    }

}
