package cn.iocoder.yudao.module.knowledge.service.review.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.ingestion.api.IngestionApi;
import cn.iocoder.yudao.module.ingestion.api.dto.ChunkRespDTO;
import cn.iocoder.yudao.module.knowledge.controller.admin.review.vo.ReviewItemPageReqVO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.review.ReviewItemDO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.version.AiDocVersionDO;
import cn.iocoder.yudao.module.knowledge.dal.mysql.review.ReviewItemMapper;
import cn.iocoder.yudao.module.knowledge.enums.review.ReviewItemStatusEnum;
import cn.iocoder.yudao.module.knowledge.service.knowledge.AiDocumentService;
import cn.iocoder.yudao.module.knowledge.service.review.ReviewItemService;
import cn.iocoder.yudao.module.knowledge.service.version.AiDocVersionService;
import cn.iocoder.yudao.module.model.api.ModelApi;
import cn.iocoder.yudao.module.model.api.dto.ModelChatReqDTO;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static cn.iocoder.yudao.module.knowledge.enums.ErrorCodeConstants.REVIEW_EXTRACT_FAILED;
import static cn.iocoder.yudao.module.knowledge.enums.ErrorCodeConstants.VERSION_NOT_EXISTS;
import static cn.iocoder.yudao.module.knowledge.enums.ErrorCodeConstants.REVIEW_ITEM_NOT_EXISTS;
import static cn.iocoder.yudao.module.knowledge.enums.ErrorCodeConstants.REVIEW_ITEM_STATUS_ERROR;
import static cn.iocoder.yudao.module.knowledge.enums.ErrorCodeConstants.REVIEW_PRICE_DOUBLE_REQUIRED;
import static cn.iocoder.yudao.module.knowledge.enums.ErrorCodeConstants.REVIEW_PRICE_SAME_REVIEWER;
import static cn.iocoder.yudao.module.knowledge.enums.ErrorCodeConstants.REVIEW_REASON_REQUIRED;

/**
 * 审核条目服务: LLM 抽取 -> 分级 -> 分流(REVIEW / 自动发布)
 */
@Slf4j
@Service
public class ReviewItemServiceImpl implements ReviewItemService {

    private static final String EXTRACT_SYSTEM_PROMPT = """
            你是企业客服知识库的"知识条目抽取器"。给定若干文档片段(chunk), 抽取其中"可以作为独立知识点被审核的知识条目"。
            抽取规则:
            1. 只抽取有实质业务含义的条目(价格、保修、政策、流程、FAQ 问答、法务条款等), 忽略纯叙述性文字。
            2. 每条输出: item_type(POLICY=政策条款/PRICE=价格/LEGAL=法务/FAQ=问答/SOP=流程步骤), title(一句话主题), content(条目原文表述, 200字内), confidence(0~1, AI置信度), risk_level(HIGH/MED/LOW)。
            3. 必须输出合法 JSON 数组, 不要输出任何其他文字。格式: [{"item_type":"PRICE","title":"换屏一口价","content":"官方换屏一口价 ¥699","confidence":0.95,"risk_level":"HIGH"}]
            """;

    private static final int BATCH_SIZE = 10;

    /** 置信度低于该值强制人工审核(BR-006) */
    private static final BigDecimal CONFIDENCE_MANUAL_THRESHOLD = new BigDecimal("0.85");

    @Resource
    private ReviewItemMapper reviewItemMapper;
    @Resource
    private AiDocVersionService aiDocVersionService;
    @Resource
    private AiDocumentService aiDocumentService;
    @Resource
    private IngestionApi ingestionApi;
    @Resource
    private ModelApi modelApi;

    @Override
    @Transactional
    public void processAfterParsed(Long versionId) {
        List<ReviewItemDO> items = extractItems(versionId);
        boolean hasRequired = items.stream().anyMatch(item -> Boolean.TRUE.equals(item.getMustReview()));
        Long docId = aiDocVersionService.getVersion(versionId).getDocId();
        if (hasRequired) {
            // 有必审条目 -> 提交审核; 文档状态回 REVIEW(重试场景下此前可能为 FAILED)
            aiDocVersionService.submitForReview(versionId);
            aiDocumentService.updateParseStatus(docId, "REVIEW", null, null);
            log.info("[processAfterParsed][版本 {} 含必审条目 {} 条, 进入审核]", versionId,
                    items.stream().filter(i -> Boolean.TRUE.equals(i.getMustReview())).count());
        } else {
            // 无必审条目(FAQ/SOP 且置信度达标) -> 自动发布
            aiDocVersionService.publish(versionId);
            log.info("[processAfterParsed][版本 {} 无必审条目, 自动发布]", versionId);
        }
    }

    @Override
    @Transactional
    public void retryExtract(Long versionId) {
        processAfterParsed(versionId);
    }

    @Override
    @Transactional
    public void retryExtractByDocId(Long docId) {
        AiDocVersionDO version = aiDocVersionService.getLatestVersion(docId);
        if (version == null) {
            throw new ServiceException(VERSION_NOT_EXISTS);
        }
        retryExtract(version.getId());
    }

    @Override
    @Transactional
    public List<ReviewItemDO> extractItems(Long versionId) {
        Long docId = aiDocVersionService.getVersion(versionId).getDocId();
        // 1. 拉取该版本全部 chunk
        List<ChunkRespDTO> chunks = ingestionApi.getChunksByVersion(versionId).getCheckedData();
        if (CollUtil.isEmpty(chunks)) {
            return List.of();
        }
        // 2. 幂等: 清旧条目
        reviewItemMapper.deleteByVersionId(versionId);
        // 3. 分批抽取
        List<ReviewItemDO> items = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i += BATCH_SIZE) {
            List<ChunkRespDTO> batch = chunks.subList(i, Math.min(i + BATCH_SIZE, chunks.size()));
            items.addAll(extractBatch(docId, versionId, batch));
        }
        // 4. 落库
        if (CollUtil.isNotEmpty(items)) {
            reviewItemMapper.insertBatch(items);
        }
        log.info("[extractItems][版本 {} 抽取条目 {} 条]", versionId, items.size());
        return items;
    }

    private List<ReviewItemDO> extractBatch(Long docId, Long versionId, List<ChunkRespDTO> batch) {
        StringBuilder sb = new StringBuilder();
        for (ChunkRespDTO chunk : batch) {
            sb.append("[chunk_").append(chunk.getId()).append("]\n").append(chunk.getContent()).append("\n\n");
        }
        ModelChatReqDTO req = new ModelChatReqDTO();
        req.setSystem(EXTRACT_SYSTEM_PROMPT);
        req.setUser(sb.toString());
        String resp = modelApi.chat(req).getCheckedData();
        JSONArray array = parseExtractJson(resp);
        List<ReviewItemDO> items = new ArrayList<>();
        for (Object o : array) {
            if (!(o instanceof JSONObject obj)) {
                continue; // 模型输出非对象元素时跳过, 不中断整批
            }
            ReviewItemDO item = new ReviewItemDO();
            item.setVersionId(versionId);
            item.setDocId(docId);
            // 归一化为全大写, 保证与 mustReview/PRICE 双人复核等比较一致(LLM 可能输出小写)
            String type = StrUtil.nullToEmpty(obj.getStr("item_type", "POLICY")).toUpperCase();
            item.setItemType(type);
            item.setTitle(StrUtil.sub(obj.getStr("title", "未命名条目"), 0, 255));
            item.setContent(StrUtil.sub(obj.getStr("content", ""), 0, 2000));
            item.setRiskLevel(StrUtil.nullToDefault(obj.getStr("risk_level", "MED"), "MED").toUpperCase());
            BigDecimal confidence = obj.getBigDecimal("confidence");
            // 置信度归一化到 [0,1](防止模型输出越界导致 decimal(4,3) 落库失败)
            if (confidence != null) {
                if (confidence.compareTo(BigDecimal.ZERO) < 0) {
                    confidence = BigDecimal.ZERO;
                } else if (confidence.compareTo(BigDecimal.ONE) > 0) {
                    confidence = BigDecimal.ONE;
                }
            }
            item.setAiConfidence(confidence);
            // 分级规则(BR-006): POLICY/PRICE/LEGAL 必审; 置信度缺失或 < 0.85 强制人工(fail-closed)
            boolean mustReview = "POLICY".equals(type) || "PRICE".equals(type) || "LEGAL".equals(type)
                    || confidence == null || confidence.compareTo(CONFIDENCE_MANUAL_THRESHOLD) < 0;
            item.setMustReview(mustReview);
            item.setStatus(ReviewItemStatusEnum.PENDING.getStatus());
            items.add(item);
        }
        return items;
    }

    private JSONArray parseExtractJson(String resp) {
        // 兼容模型输出带 ```json 包裹
        String content = resp == null ? "" : resp.trim();
        if (content.startsWith("```")) {
            content = content.replaceAll("(?i)```json", "").replaceAll("```", "").trim();
        }
        int start = content.indexOf('[');
        int end = content.lastIndexOf(']');
        if (start >= 0 && end > start) {
            content = content.substring(start, end + 1);
        }
        try {
            return JSONUtil.parseArray(content);
        } catch (Exception e) {
            // 解析失败必须抛错(走 FAILED + 可重试), 不能静默返回空导致"无必审条目 -> 自动发布"绕过门禁
            log.error("[parseExtractJson][解析失败, 原文: {}]", resp);
            throw new ServiceException(REVIEW_EXTRACT_FAILED);
        }
    }

    @Override
    public PageResult<ReviewItemDO> getReviewItemPage(ReviewItemPageReqVO pageReqVO) {
        return reviewItemMapper.selectPage(pageReqVO, new LambdaQueryWrapperX<ReviewItemDO>()
                .eqIfPresent(ReviewItemDO::getDocId, pageReqVO.getDocId())
                .eqIfPresent(ReviewItemDO::getVersionId, pageReqVO.getVersionId())
                .eqIfPresent(ReviewItemDO::getStatus, pageReqVO.getStatus())
                .eqIfPresent(ReviewItemDO::getItemType, pageReqVO.getItemType())
                .eqIfPresent(ReviewItemDO::getRiskLevel, pageReqVO.getRiskLevel())
                // 风险降序: HIGH > MED > LOW(字母序为 HIGH<LOW<MED, 需 FIELD 显式排序), 同风险按 id 倒序
                .last("ORDER BY FIELD(risk_level, 'HIGH', 'MED', 'LOW'), id DESC"));
    }

    @Override
    public void approve(Long id) {
        ReviewItemDO item = getItem(id);
        if (!ReviewItemStatusEnum.PENDING.getStatus().equals(item.getStatus())) {
            throw new ServiceException(REVIEW_ITEM_STATUS_ERROR);
        }
        ReviewItemDO update = new ReviewItemDO();
        update.setId(id);
        update.setStatus(ReviewItemStatusEnum.APPROVED.getStatus());
        update.setReviewer(currentNickname());
        update.setReviewTime(LocalDateTime.now());
        reviewItemMapper.updateById(update);
        log.info("[approve][条目 {} 通过]", id);
    }

    @Override
    public void approveSecond(Long id) {
        ReviewItemDO item = getItem(id);
        if (!"PRICE".equals(item.getItemType())) {
            throw new ServiceException(REVIEW_PRICE_DOUBLE_REQUIRED);
        }
        // 原子条件更新防并发(TOCTOU): 校验并入 WHERE, 以影响行数为准
        String reviewer = currentNickname();
        int rows = reviewItemMapper.update(null, new LambdaUpdateWrapper<ReviewItemDO>()
                .eq(ReviewItemDO::getId, id)
                .eq(ReviewItemDO::getStatus, ReviewItemStatusEnum.APPROVED.getStatus())
                .isNull(ReviewItemDO::getReviewer2)
                .ne(ReviewItemDO::getReviewer, reviewer)
                .set(ReviewItemDO::getReviewer2, reviewer));
        if (rows == 0) {
            // 未更新: 状态不对/已双人复核/同人复核
            if (StrUtil.equals(item.getReviewer(), reviewer)) {
                throw new ServiceException(REVIEW_PRICE_SAME_REVIEWER);
            }
            throw new ServiceException(REVIEW_ITEM_STATUS_ERROR);
        }
        log.info("[approveSecond][条目 {} 双人复核完成]", id);
    }

    @Override
    public void reject(Long id, String reason) {
        if (StrUtil.isBlank(reason)) {
            throw new ServiceException(REVIEW_REASON_REQUIRED);
        }
        ReviewItemDO item = getItem(id);
        if (!ReviewItemStatusEnum.PENDING.getStatus().equals(item.getStatus())) {
            throw new ServiceException(REVIEW_ITEM_STATUS_ERROR);
        }
        ReviewItemDO update = new ReviewItemDO();
        update.setId(id);
        update.setStatus(ReviewItemStatusEnum.REJECTED.getStatus());
        update.setRejectReason(reason);
        update.setReviewer(currentNickname());
        update.setReviewTime(LocalDateTime.now());
        reviewItemMapper.updateById(update);
        log.info("[reject][条目 {} 驳回: {}]", id, reason);
    }

    private ReviewItemDO getItem(Long id) {
        ReviewItemDO item = reviewItemMapper.selectById(id);
        if (item == null) {
            throw new ServiceException(REVIEW_ITEM_NOT_EXISTS);
        }
        return item;
    }

    private String currentNickname() {
        return SecurityFrameworkUtils.getLoginUserNickname();
    }

}
