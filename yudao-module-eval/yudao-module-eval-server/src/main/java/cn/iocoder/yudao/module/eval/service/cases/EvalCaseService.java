package cn.iocoder.yudao.module.eval.service.cases;

import cn.hutool.core.util.StrUtil;
import java.util.stream.Collectors;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import cn.iocoder.yudao.module.model.api.dto.ModelChatReqDTO;
import cn.iocoder.yudao.module.model.api.ModelApi;
import cn.iocoder.yudao.module.knowledge.api.dto.KnowledgePublishedChunkDTO;
import cn.iocoder.yudao.module.knowledge.api.KnowledgeApi;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONArray;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.eval.controller.admin.cases.vo.EvalCasePageReqVO;
import cn.iocoder.yudao.module.eval.controller.admin.cases.vo.EvalCaseSaveReqVO;
import cn.iocoder.yudao.module.eval.controller.admin.cases.vo.EvalCaseUpdateReqVO;
import cn.iocoder.yudao.module.eval.dal.dataobject.cases.EvalCaseDO;
import cn.iocoder.yudao.module.eval.dal.mysql.cases.EvalCaseMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

import static cn.iocoder.yudao.module.eval.enums.ErrorCodeConstants.EVAL_CASE_NOT_EXISTS;

/**
 * 评测用例 Service: 考题 CRUD + 反馈生成用例
 */
@Slf4j
@Service
@Validated
public class EvalCaseService {

    @Resource
    private EvalCaseMapper evalCaseMapper;

    /**
     * 创建评测用例
     */
    public Long createCase(EvalCaseSaveReqVO createReqVO) {
        EvalCaseDO evalCase = BeanUtils.toBean(createReqVO, EvalCaseDO.class);
        evalCase.setGoldChunks(toGoldChunksJson(createReqVO.getGoldChunks()));
        evalCaseMapper.insert(evalCase);
        log.info("[createCase][创建评测用例 {}: {}]", evalCase.getId(), evalCase.getQuestion());
        return evalCase.getId();
    }

    /**
     * 更新评测用例
     */
    public void updateCase(EvalCaseUpdateReqVO updateReqVO) {
        // 校验存在
        validateCaseExists(updateReqVO.getId());
        // 更新(仅拷贝非空字段, 避免覆盖未提交字段)
        EvalCaseDO update = BeanUtils.toBean(updateReqVO, EvalCaseDO.class);
        update.setGoldChunks(toGoldChunksJson(updateReqVO.getGoldChunks()));
        evalCaseMapper.updateById(update);
    }

    /**
     * 删除评测用例
     */
    public void deleteCase(Long id) {
        // 校验存在
        validateCaseExists(id);
        evalCaseMapper.deleteById(id);
    }

    /**
     * 获得评测用例(不存在则抛 EVAL_CASE_NOT_EXISTS)
     */
    public EvalCaseDO getCase(Long id) {
        return validateCaseExists(id);
    }

    /**
     * 获得评测用例分页
     */
    public PageResult<EvalCaseDO> getCasePage(EvalCasePageReqVO pageReqVO) {
        return evalCaseMapper.selectPage(pageReqVO);
    }

    /**
     * 反馈转评测用例(chat 模块反馈落库后 RPC 调用; 标准答案/标准证据待人工补充)
     *
     * @param kbId              知识库编号
     * @param question          问题(来自反馈对应的用户消息)
     * @param sourceFeedbackId  来源反馈编号(ai_feedback.id)
     * @return 新用例编号
     */
    public Long createCaseFromFeedback(Long kbId, String question, Long sourceFeedbackId) {
        EvalCaseDO evalCase = EvalCaseDO.builder()
                .question(question)
                .kbId(kbId)
                .sourceFeedback(sourceFeedbackId)
                .category("综合")
                .build();
        evalCaseMapper.insert(evalCase);
        log.info("[createCaseFromFeedback][反馈 {} 生成评测用例 {}]", sourceFeedbackId, evalCase.getId());
        return evalCase.getId();
    }

    private EvalCaseDO validateCaseExists(Long id) {
        EvalCaseDO evalCase = evalCaseMapper.selectById(id);
        if (evalCase == null) {
            throw new ServiceException(EVAL_CASE_NOT_EXISTS);
        }
        return evalCase;
    }

    /**
     * List<Long> -> JSON 数组字符串; 空/null 存 null
     */
    private String toGoldChunksJson(List<Long> goldChunks) {
        if (goldChunks == null || goldChunks.isEmpty()) {
            return null;
        }
        return JSONUtil.toJsonStr(goldChunks);
    }

    // ========== 自动生成用例(入库后一键评测) ==========

    /** 该知识库已有用例数达到上限后不再自动生成(避免重复堆积) */
    private static final int MAX_AUTO_CASES_PER_KB = 5;

    /** 单次生成采样的片段上限(跨文档均匀抽取, 避免单文档表格行占满) */
    private static final int GENERATE_SAMPLE_LIMIT = 12;

    /** 用例分类标记(自动生成) */
    private static final String CATEGORY_AUTO = "自动生成";

    private static final String GENERATE_SYSTEM_PROMPT = """
            你是知识库评测命题员。根据给定的知识片段(含片段编号), 生成客户咨询题:
            {"cases":[{"question":"客户会怎么问?","goldAnswer":"依据片段的标准答案","goldChunks":[123,124]}]}
            要求:
            1. 每题 question 是客户口语化咨询, 且必须能仅凭给定片段作答;
            2. goldAnswer 严格依据片段内容给出标准答案, 不得编造片段外信息;
            3. goldChunks 列出该题作答所需的全部相关片段编号(从给定片段中选, 1~3 个, 覆盖答案依据);
            4. 每题尽量选取不同主题, 覆盖给定片段, 5~8 题; 只输出合法 JSON, 不要其他文字。
            """;

    @Resource
    private KnowledgeApi knowledgeApi;
    @Resource
    private ModelApi modelApi;

    /**
     * 从知识库已发布内容自动生成评测用例(入库后一键评测用)
     * <p>
     * 规则: 该库已有用例数 &gt;= MAX_AUTO_CASES_PER_KB(5) 时跳过(返回 0);
     * 采样已发布片段(≤8) → LLM 命题(question/goldAnswer/goldChunks=相关片段集合 1~3 个) → 落库。
     * 失败语义: 任何失败返回 -1, 绝不抛出。
     *
     * @param kbId 知识库编号
     * @return 新生成用例数; 无已发布内容/已达上限返回 0; 失败返回 -1
     */
    public int generateCases(Long kbId) {
        try {
            // 1. 已发布片段采样
            List<KnowledgePublishedChunkDTO> chunks = knowledgeApi.getPublishedChunks(kbId).getCheckedData();
            if (CollUtil.isEmpty(chunks)) {
                log.warn("[generateCases][知识库 {} 无已发布内容, 跳过生成]", kbId);
                return 0;
            }
            // 2. 幂等: 已有用例足够时跳过
            Long existing = evalCaseMapper.selectCount(new LambdaQueryWrapperX<EvalCaseDO>()
                    .eq(EvalCaseDO::getKbId, kbId));
            if (existing != null && existing >= MAX_AUTO_CASES_PER_KB) {
                log.info("[generateCases][知识库 {} 已有 {} 个用例, 跳过自动生成]", kbId, existing);
                return 0;
            }
            // 3. 跨文档均匀采样(等距取点, 覆盖多文档而非前 N 条) + LLM 命题
            List<KnowledgePublishedChunkDTO> sample = evenlySample(chunks, GENERATE_SAMPLE_LIMIT);
            ModelChatReqDTO req = new ModelChatReqDTO();
            req.setSystem(GENERATE_SYSTEM_PROMPT);
            req.setUser(JSONUtil.toJsonStr(sample.stream().map(c -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("chunkId", c.getChunkId());
                m.put("content", c.getContent());
                return m;
            }).toList()));
            req.setTemperature(0.0); // 结构化输出确定性
            String resp = modelApi.chat(req).getCheckedData();
            if (StrUtil.isBlank(resp)) {
                log.warn("[generateCases][知识库 {} LLM 返回为空, 跳过]", kbId);
                return -1;
            }
            // 4. 解析 + 落库(仅认采样片段编号)
            Set<Long> sampleIds = sample.stream().map(KnowledgePublishedChunkDTO::getChunkId)
                    .collect(Collectors.toSet());
            int start = resp.indexOf('{');
            int end = resp.lastIndexOf('}');
            if (start < 0 || end <= start) {
                log.warn("[generateCases][知识库 {} LLM 输出无法解析, 跳过; 原文: {}]", kbId, resp);
                return -1;
            }
            JSONArray arr = JSONUtil.parseObj(resp.substring(start, end + 1)).getJSONArray("cases");
            if (arr == null) {
                log.warn("[generateCases][知识库 {} LLM 输出无 cases, 跳过; 原文: {}]", kbId, resp);
                return -1;
            }
            int inserted = 0;
            for (Object o : arr) {
                if (!(o instanceof JSONObject obj)) {
                    continue;
                }
                String question = StrUtil.nullToEmpty(obj.getStr("question")).trim();
                String goldAnswer = StrUtil.nullToEmpty(obj.getStr("goldAnswer")).trim();
                if (StrUtil.isBlank(question) || StrUtil.isBlank(goldAnswer)) {
                    continue;
                }
                // goldChunks: LLM 选定的相关片段集合(仅认采样片段编号; 空则跳过该题)
                JSONArray goldArr = obj.getJSONArray("goldChunks");
                List<Long> goldChunks = new ArrayList<>();
                if (goldArr != null) {
                    for (Object g : goldArr) {
                        if (g instanceof Number n && sampleIds.contains(n.longValue())) {
                            goldChunks.add(n.longValue());
                        }
                    }
                }
                if (goldChunks.isEmpty()) {
                    continue;
                }
                EvalCaseDO evalCase = EvalCaseDO.builder()
                        .question(StrUtil.maxLength(question, 500))
                        .goldAnswer(StrUtil.maxLength(goldAnswer, 2000))
                        .goldChunks(JSONUtil.toJsonStr(goldChunks))
                        .kbId(kbId)
                        .category(CATEGORY_AUTO)
                        .build();
                evalCaseMapper.insert(evalCase);
                inserted++;
            }
            log.info("[generateCases][知识库 {} 自动生成用例完成: {} 个]", kbId, inserted);
            return inserted;
        } catch (Exception e) {
            log.warn("[generateCases][知识库 {} 自动生成用例失败: {}]", kbId, e.getMessage(), e);
            return -1;
        }
    }

    /** 跨文档均匀采样: 等距取点最多 limit 个, 避免前 N 条全来自同一文档 */
    private List<KnowledgePublishedChunkDTO> evenlySample(List<KnowledgePublishedChunkDTO> chunks, int limit) {
        int size = chunks.size();
        if (size <= limit) {
            return chunks;
        }
        List<KnowledgePublishedChunkDTO> sample = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            int idx = (int) Math.round(i * (size - 1) / (double) (limit - 1));
            sample.add(chunks.get(idx));
        }
        return sample;
    }

}
