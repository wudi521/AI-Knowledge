package cn.iocoder.yudao.module.evidence.service.slot;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.evidence.service.prompt.PromptSupport;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeSlotDefinitionDTO;
import cn.iocoder.yudao.module.model.api.ModelApi;
import cn.iocoder.yudao.module.model.api.dto.ModelChatReqDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 知识库槽位检测器(检索之前; 数据驱动: 槽位定义来自 KnowledgeApi, 提示词模板固定不写死槽位)
 * <p>
 * 流程: 取目标 KB 启用槽位定义 → 按 slot_code 并集去重(同编码同语义, 首个出现行优先) →
 * LLM 单次抽取 {applicable, extracted, missing} → 代码兜底 required 校验(LLM 漏报的必填缺失补齐)。
 * <p>
 * 降级原则(永不阻断): 任何异常/解析失败/无定义 → 返回 null, 由调用方跳过检测走原流程。
 */
@Slf4j
@Component
public class SlotDetector {

    /** 系统提示词模板(固定; 槽位定义 JSON 动态注入) */
    private static final String SYSTEM_PROMPT = """
            你是客服问题的"条件抽取器"。知识库定义了以下槽位(条件维度)定义:
            {defs}
            首要规则: 泛指/模糊信息一律视为未提供, 宁可留空不要猜测——仅说产品类别(如"手机/电脑/设备", 未提具体型号)不算已提供品牌; 故障性质模糊(如只说"坏了")不算已提供。
            对每个槽位, 按 description 的判定标准判断问题中是否已提供该信息:
            1. 已提供 → extracted 填入(口语说法按 description 归类, 如"摔碎屏"→"意外损坏");
            2. 未提供 → extracted 不含该键; required=true 则列入 missing;
            3. applicable: 问题与槽位集领域无关(如"你好"、闲聊)为 false, 此时 missing 恒为空;
            4. 只输出合法 JSON, 不要其他文字。
            示例: 问题"X100 Pro 碎屏了, 刚买2个月" → {"applicable": true, "extracted": {"brand": "X100 Pro", "faultType": "意外损坏", "purchaseTime": "刚买2个月"}, "missing": []}
            输出格式: {"applicable": true, "extracted": {"brand": "苹果13"}, "missing": [{"code":"faultType","name":"故障性质"}]}
            """;

    @Resource
    private KnowledgeApi knowledgeApi;

    @Resource
    private ModelApi modelApi;

    @Resource
    private PromptSupport promptSupport;

    /**
     * 检测: 抽取槽位值并找出缺失必填槽位
     *
     * @param query 客户问题
     * @param kbIds 目标知识库编号列表(非空由调用方保证)
     * @return 检测结果; 无定义/失败 → null(调用方跳过检测)
     */
    public SlotDetectionResult detect(String query, List<Long> kbIds) {
        try {
            // 1. 取定义(RPC 失败由外层 catch 降级)
            List<KnowledgeSlotDefinitionDTO> defs = knowledgeApi.getSlotDefinitions(kbIds).getCheckedData();
            if (defs == null || defs.isEmpty()) {
                return null;
            }
            // 2. 按 slot_code 并集去重(同编码同语义, 首个出现行优先; 保序)
            Map<String, KnowledgeSlotDefinitionDTO> unique = new LinkedHashMap<>();
            for (KnowledgeSlotDefinitionDTO def : defs) {
                unique.putIfAbsent(def.getSlotCode(), def);
            }
            List<KnowledgeSlotDefinitionDTO> slotDefs = new ArrayList<>(unique.values());

            // 3. LLM 单次抽取
            String resp = modelApi.chat(buildReq(query, slotDefs)).getCheckedData();
            JSONObject json = parseJson(resp);
            if (json == null) {
                log.warn("[detect][query({}) LLM 输出无法解析, 跳过槽位检测]", query);
                return null;
            }
            boolean applicable = Boolean.TRUE.equals(json.getBool("applicable"));

            // 4. 抽取值(容错: 空对象/空值按未抽取)
            Map<String, String> extracted = new LinkedHashMap<>();
            JSONObject extractedJson = json.getJSONObject("extracted");
            if (extractedJson != null) {
                for (String code : extractedJson.keySet()) {
                    String value = extractedJson.getStr(code);
                    extracted.put(code, value == null ? "" : value.trim());
                }
            }

            // 5. 代码兜底: required 且未抽到值 → 必填缺失(与 LLM 报告并集, 按 sort 升序)
            Set<String> missingCodes = new LinkedHashSet<>();
            if (applicable) {
                // 5a. LLM 报告的 missing(仅认定义中存在且必填的槽位, 可选槽位不触发反问)
                JSONArray llmMissing = json.getJSONArray("missing");
                if (llmMissing != null) {
                    for (Object item : llmMissing) {
                        if (item instanceof JSONObject obj && obj.getStr("code") != null) {
                            String code = obj.getStr("code");
                            if (unique.containsKey(code)
                                    && Boolean.TRUE.equals(unique.get(code).getRequired())
                                    && missingCodes.add(code)) {
                                // 名称以定义为准, 不信任 LLM 给的 name
                            }
                        }
                    }
                }
                // 5b. 代码兜底: 必填但未抽到值
                for (KnowledgeSlotDefinitionDTO def : slotDefs) {
                    if (Boolean.TRUE.equals(def.getRequired())
                            && StrUtil.isBlank(extracted.get(def.getSlotCode()))) {
                        missingCodes.add(def.getSlotCode());
                    }
                }
            }

            // 6. 组装(缺失按定义 sort 升序; slotName 一律取自定义)
            Map<String, Integer> sortMap = slotDefs.stream()
                    .collect(Collectors.toMap(KnowledgeSlotDefinitionDTO::getSlotCode,
                            d -> d.getSort() == null ? 0 : d.getSort()));
            List<SlotDetectionResult.MissingSlot> missing = missingCodes.stream()
                    .sorted((a, b) -> Integer.compare(sortMap.getOrDefault(a, 0), sortMap.getOrDefault(b, 0)))
                    .map(code -> new SlotDetectionResult.MissingSlot(code, unique.get(code).getSlotName()))
                    .collect(Collectors.toList());

            return SlotDetectionResult.builder()
                    .applicable(applicable)
                    .extracted(extracted)
                    .missing(missing)
                    .build();
        } catch (Exception e) {
            log.warn("[detect][query({}) 槽位检测失败, 跳过检测: {}]", query, e.getMessage());
            return null;
        }
    }

    /** 组装 LLM 请求: 提示词模板固定, 槽位定义序列化为 JSON 注入 */
    private ModelChatReqDTO buildReq(String query, List<KnowledgeSlotDefinitionDTO> slotDefs) {
        List<Map<String, Object>> defsJson = slotDefs.stream().map(def -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", def.getSlotCode());
            m.put("name", def.getSlotName());
            m.put("description", def.getDescription());
            m.put("required", Boolean.TRUE.equals(def.getRequired()));
            m.put("sort", def.getSort());
            return m;
        }).collect(Collectors.toList());
        ModelChatReqDTO req = new ModelChatReqDTO();
        String sys = promptSupport.get("slot-detect", SYSTEM_PROMPT);
        req.setSystem(sys.replace("{defs}", JSONUtil.toJsonStr(defsJson)));
        req.setUser("问题: " + query);
        req.setTemperature(0.0); // 结构化抽取: 置 0 保证确定性(默认 0.2 采样会造成抽取得失不稳定)
        return req;
    }

    /** 截取首个 { 到最后一个 } 之间的内容并解析(兼容 LLM 输出带前后缀说明) */
    private JSONObject parseJson(String resp) {
        if (StrUtil.isBlank(resp)) {
            return null;
        }
        int start = resp.indexOf('{');
        int end = resp.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return JSONUtil.parseObj(resp.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }

}
