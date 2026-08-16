package cn.iocoder.yudao.module.knowledge.service.review;

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
    java.util.List<cn.iocoder.yudao.module.knowledge.dal.dataobject.review.ReviewItemDO> extractItems(Long versionId);

}
