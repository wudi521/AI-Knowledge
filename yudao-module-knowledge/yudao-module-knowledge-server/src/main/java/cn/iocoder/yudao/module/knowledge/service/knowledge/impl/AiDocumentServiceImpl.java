package cn.iocoder.yudao.module.knowledge.service.knowledge.impl;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.ingestion.api.IngestionApi;
import cn.iocoder.yudao.module.knowledge.controller.admin.knowledge.vo.AiDocumentPageReqVO;
import cn.iocoder.yudao.module.knowledge.controller.admin.knowledge.vo.AiDocumentSaveReqVO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.AiDocumentDO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.version.AiDocVersionDO;
import cn.iocoder.yudao.module.knowledge.dal.mysql.knowledge.AiDocumentMapper;
import cn.iocoder.yudao.module.knowledge.dal.mysql.version.AiDocVersionMapper;
import cn.iocoder.yudao.module.knowledge.mq.KnowledgeIngestProducer;
import cn.iocoder.yudao.module.knowledge.service.knowledge.AiDocumentService;
import cn.iocoder.yudao.module.knowledge.service.review.ReviewItemService;
import cn.iocoder.yudao.module.knowledge.service.version.AiDocVersionService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import static cn.iocoder.yudao.module.knowledge.enums.ErrorCodeConstants.VERSION_DOC_MISMATCH;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.knowledge.enums.ErrorCodeConstants.DOCUMENT_NOT_EXISTS;

/**
 * AI 文档 Service 实现
 */
@Slf4j
@Service
@Validated
public class AiDocumentServiceImpl implements AiDocumentService {

    @Resource
    private AiDocumentMapper aiDocumentMapper;

    @Resource
    private KnowledgeIngestProducer knowledgeIngestProducer;

    @Resource
    private IngestionApi ingestionApi;

    @Resource
    private AiDocVersionService aiDocVersionService;

    @Resource
    private ReviewItemService reviewItemService;

    @Resource
    private AiDocVersionMapper aiDocVersionMapper;

    @Override
    public Long createAiDocument(AiDocumentSaveReqVO createReqVO) {
        AiDocumentDO doc = BeanUtils.toBean(createReqVO, AiDocumentDO.class);
        // 初始状态: 待解析
        if (doc.getParseStatus() == null) {
            doc.setParseStatus("PENDING");
        }
        aiDocumentMapper.insert(doc);
        // 创建 DRAFT 版本(版本状态机)
        aiDocVersionService.createVersion(doc.getId());
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
        // 级联删除版本记录
        aiDocVersionMapper.delete(new LambdaQueryWrapper<AiDocVersionDO>().eq(AiDocVersionDO::getDocId, id));
        // 最后删除文档行
        aiDocumentMapper.deleteById(id);
    }

    @Override
    public AiDocumentDO getAiDocument(Long id) {
        return aiDocumentMapper.selectById(id);
    }

    @Override
    public PageResult<AiDocumentDO> getAiDocumentPage(AiDocumentPageReqVO pageReqVO) {
        PageResult<AiDocumentDO> pageResult = aiDocumentMapper.selectPage(pageReqVO);
        // 填充当前版本号/状态(批量查版本)
        List<Long> docIds = pageResult.getList().stream().map(AiDocumentDO::getId).toList();
        if (!docIds.isEmpty()) {
            Map<Long, AiDocVersionDO> latestMap = aiDocVersionMapper.selectList(new LambdaQueryWrapper<AiDocVersionDO>()
                            .in(AiDocVersionDO::getDocId, docIds))
                    .stream().collect(Collectors.toMap(
                            v -> v.getDocId(), v -> v, (a, b) -> a.getId() >= b.getId() ? a : b));
            pageResult.getList().forEach(doc -> {
                AiDocVersionDO v = latestMap.get(doc.getId());
                if (v != null) {
                    doc.setVersionNo(v.getVersionNo());
                    doc.setVersionStatus(v.getStatus());
                }
            });
        }
        return pageResult;
    }

    @Override
    public void notifyParsed(Long documentId, Long versionId) {
        validateAiDocumentExists(documentId);
        // 严格绑定 ingestion 传入的版本(不能按最新推断, 防止旧消息重投误发布新版本)
        AiDocVersionDO version = aiDocVersionService.getVersion(versionId);
        if (!documentId.equals(version.getDocId())) {
            throw new ServiceException(VERSION_DOC_MISMATCH);
        }
        // 抽取失败由 reviewItemService 内部兜底: 置文档 FAILED, 不向上抛(ingestion 无需感知)
        try {
            reviewItemService.processAfterParsed(version.getId());
        } catch (Exception e) {
            log.error("[notifyParsed][文档 {} 版本 {} 审核处理失败]", documentId, versionId, e);
            updateParseStatus(documentId, "FAILED", null, StrUtil.sub(e.getMessage(), 0, 500));
        }
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
