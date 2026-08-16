package cn.iocoder.yudao.module.knowledge.service.review;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.knowledge.controller.admin.review.vo.ReviewItemPageReqVO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.review.ReviewItemDO;

import java.util.List;

/**
 * 审核条目服务
 */
public interface ReviewItemService {

    /**
     * 解析完成后的处理入口(notifyParsed 调用):
     * 抽取条目 -> 有必审则提交审核(REVIEW), 无必审则自动发布
     *
     * @param versionId 版本编号
     */
    void processAfterParsed(Long versionId);

    /**
     * 重新抽取审核条目(LLM 抽取失败后重试)
     *
     * @param versionId 版本编号
     */
    void retryExtract(Long versionId);

    /**
     * LLM 抽取该版本的审核条目(幂等: 先清后插)
     *
     * @param versionId 版本编号
     * @return 抽取出的条目
     */
    List<ReviewItemDO> extractItems(Long versionId);

    /**
     * 分页查询审核条目(审核台四 tab 共用)
     *
     * @param pageReqVO 分页请求
     * @return 审核条目分页
     */
    PageResult<ReviewItemDO> getReviewItemPage(ReviewItemPageReqVO pageReqVO);

    /**
     * 通过条目(PRICE 类型仅完成单人, 需双人复核)
     *
     * @param id 条目编号
     */
    void approve(Long id);

    /**
     * 价格类双人复核(第二人)
     *
     * @param id 条目编号
     */
    void approveSecond(Long id);

    /**
     * 驳回条目(必填原因)
     *
     * @param id     条目编号
     * @param reason 驳回原因
     */
    void reject(Long id, String reason);

}
