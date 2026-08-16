package cn.iocoder.yudao.module.knowledge.service.knowledge.impl;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.ingestion.api.IngestionApi;
import cn.iocoder.yudao.module.knowledge.controller.admin.knowledge.vo.AiDocumentPageReqVO;
import cn.iocoder.yudao.module.knowledge.controller.admin.knowledge.vo.AiDocumentSaveReqVO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.AiDocumentDO;
import cn.iocoder.yudao.module.knowledge.dal.mysql.knowledge.AiDocumentMapper;
import cn.iocoder.yudao.module.knowledge.mq.KnowledgeIngestProducer;
import cn.iocoder.yudao.module.knowledge.service.knowledge.AiDocumentService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.knowledge.enums.ErrorCodeConstants.DOCUMENT_NOT_EXISTS;

/**
 * AI 文档 Service 实现
 */
@Service
@Validated
public class AiDocumentServiceImpl implements AiDocumentService {

    @Resource
    private AiDocumentMapper aiDocumentMapper;

    @Resource
    private KnowledgeIngestProducer knowledgeIngestProducer;

    @Resource
    private IngestionApi ingestionApi;

    @Override
    public Long createAiDocument(AiDocumentSaveReqVO createReqVO) {
        AiDocumentDO doc = BeanUtils.toBean(createReqVO, AiDocumentDO.class);
        // 初始状态: 待解析
        if (doc.getParseStatus() == null) {
            doc.setParseStatus("PENDING");
        }
        aiDocumentMapper.insert(doc);
        // 发送入库任务消息(Kafka), ingestion-server 异步解析/切分/向量化/三写
        knowledgeIngestProducer.sendDocumentIngest(doc.getId());
        return doc.getId();
    }

    @Override
    public void deleteAiDocument(Long id) {
        validateAiDocumentExists(id);
        // 级联清理片段数据(MySQL ai_chunk + ES + Milvus), 失败则中断删除
        CommonResult<Boolean> result = ingestionApi.deleteDocumentData(id);
        if (result.isError()) {
            throw new ServiceException(result.getCode(), result.getMsg());
        }
        // 最后删除文档行
        aiDocumentMapper.deleteById(id);
    }

    @Override
    public AiDocumentDO getAiDocument(Long id) {
        return aiDocumentMapper.selectById(id);
    }

    @Override
    public PageResult<AiDocumentDO> getAiDocumentPage(AiDocumentPageReqVO pageReqVO) {
        return aiDocumentMapper.selectPage(pageReqVO);
    }

    @Override
    public void updateParseStatus(Long id, String parseStatus, Integer chunkCount, String errorMsg) {
        validateAiDocumentExists(id);
        aiDocumentMapper.updateParseStatus(id, parseStatus, chunkCount, errorMsg);
    }

    private void validateAiDocumentExists(Long id) {
        if (aiDocumentMapper.selectById(id) == null) {
            throw exception(DOCUMENT_NOT_EXISTS);
        }
    }

}
