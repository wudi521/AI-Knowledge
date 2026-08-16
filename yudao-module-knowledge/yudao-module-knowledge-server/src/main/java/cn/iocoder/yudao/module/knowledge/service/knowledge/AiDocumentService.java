package cn.iocoder.yudao.module.knowledge.service.knowledge;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.knowledge.controller.admin.knowledge.vo.AiDocumentPageReqVO;
import cn.iocoder.yudao.module.knowledge.controller.admin.knowledge.vo.AiDocumentSaveReqVO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.AiDocumentDO;
import jakarta.validation.Valid;

/**
 * AI 文档 Service 接口
 */
public interface AiDocumentService {

    /** 创建文档 */
    Long createAiDocument(@Valid AiDocumentSaveReqVO createReqVO);

    /** 删除文档 */
    void deleteAiDocument(Long id);

    /** 获得文档 */
    AiDocumentDO getAiDocument(Long id);

    /** 获得文档分页 */
    PageResult<AiDocumentDO> getAiDocumentPage(AiDocumentPageReqVO pageReqVO);

    /**
     * 更新文档解析状态
     */
    void updateParseStatus(Long id, String parseStatus, Integer chunkCount, String errorMsg);

    /** 解析完成通知: 确保存在 DRAFT 版本并触发审核条目处理 */
    void notifyParsed(Long documentId);

}
