package cn.iocoder.yudao.module.ingestion.domain;

import cn.iocoder.yudao.module.ingestion.split.Chunk;
import cn.iocoder.yudao.module.ingestion.split.ParsedDocument;
import cn.iocoder.yudao.module.ingestion.split.SplitParams;
import cn.iocoder.yudao.module.knowledge.api.dto.KnowledgeDocumentRespDTO;

import java.util.List;

/**
 * 领域入库适配器(轻量领域扩展点, 不新增微服务):
 * 领域实现通过 Spring Bean 列表注册, DomainIngestionRegistry 按 domainCode 索引, 未找到回退 GENERAL。
 * 领域实现不得绕过租户/ACL/已发布/证据校验, 不得访问其他租户数据, 不支持运行时上传 JAR。
 */
public interface DomainIngestionAdapter {

    /** 领域代码: GENERAL/PATENT */
    String domainCode();

    /** 提取领域文档元数据(JSON 字符串, 持久化到 ai_document.domain_metadata; 无则返回 null) */
    String extractMetadata(ParsedDocument document, KnowledgeDocumentRespDTO source);

    /** 领域切分(返回领域化 Chunk; GENERAL 走通用 SplitterFactory) */
    List<Chunk> split(ParsedDocument document, SplitParams params, String domainMetadata);
}
