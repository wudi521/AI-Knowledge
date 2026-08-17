package cn.iocoder.yudao.module.knowledge.controller.admin.review;

import cn.hutool.core.collection.CollUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.knowledge.controller.admin.review.vo.ReviewItemPageReqVO;
import cn.iocoder.yudao.module.knowledge.controller.admin.review.vo.ReviewItemRespVO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.AiDocumentDO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.review.ReviewItemDO;
import cn.iocoder.yudao.module.knowledge.dal.mysql.knowledge.AiDocumentMapper;
import cn.iocoder.yudao.module.knowledge.service.review.ReviewItemService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.framework.common.util.object.BeanUtils.toBean;

@Tag(name = "管理后台 - 知识审核条目")
@RestController
@RequestMapping("/knowledge/review-item")
@Validated
public class ReviewItemController {

    @Resource
    private ReviewItemService reviewItemService;
    @Resource
    private AiDocumentMapper aiDocumentMapper;

    @GetMapping("/page")
    @Operation(summary = "审核条目分页(审核台四 tab 共用)")
    @PreAuthorize("@ss.hasPermission('knowledge:review:query')")
    public CommonResult<PageResult<ReviewItemRespVO>> getReviewItemPage(@Valid ReviewItemPageReqVO pageReqVO) {
        PageResult<ReviewItemDO> pageResult = reviewItemService.getReviewItemPage(pageReqVO);
        PageResult<ReviewItemRespVO> voPageResult = toBean(pageResult, ReviewItemRespVO.class);
        // 联表填充文档名称: 按 docId 批量查 AiDocument, 文档可能被删故 null 安全
        Set<Long> docIds = pageResult.getList().stream()
                .map(ReviewItemDO::getDocId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (CollUtil.isNotEmpty(docIds)) {
            Map<Long, String> docNameMap = aiDocumentMapper.selectBatchIds(docIds).stream()
                    .collect(Collectors.toMap(AiDocumentDO::getId, AiDocumentDO::getName, (a, b) -> a));
            for (ReviewItemRespVO respVO : voPageResult.getList()) {
                if (respVO.getDocId() != null) {
                    respVO.setDocName(docNameMap.get(respVO.getDocId()));
                }
            }
        }
        return success(voPageResult);
    }

    @PostMapping("/approve")
    @Operation(summary = "通过条目(PRICE 类型待双人复核)")
    @PreAuthorize("@ss.hasPermission('knowledge:review:update')")
    public CommonResult<Boolean> approve(@RequestParam("id") Long id) {
        reviewItemService.approve(id);
        return success(true);
    }


    @PostMapping("/retry-extract")
    @Operation(summary = "按文档重试 LLM 抽取(抽取失败后的恢复入口)")
    @PreAuthorize("@ss.hasPermission('knowledge:review:update')")
    public CommonResult<Boolean> retryExtract(@RequestParam("docId") Long docId) {
        reviewItemService.retryExtractByDocId(docId);
        return success(true);
    }

    @PostMapping("/reject")
    @Operation(summary = "驳回条目")
    @PreAuthorize("@ss.hasPermission('knowledge:review:update')")
    public CommonResult<Boolean> reject(@RequestParam("id") Long id, @RequestParam("reason") String reason) {
        reviewItemService.reject(id, reason);
        return success(true);
    }

}
