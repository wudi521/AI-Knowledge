package cn.iocoder.yudao.module.knowledge.controller.admin.ops;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.ingestion.api.IngestionApi;
import cn.iocoder.yudao.module.ingestion.api.dto.ChunkRespDTO;
import cn.iocoder.yudao.module.ingestion.api.dto.IngestionJobTraceDTO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.knowledge.AiDocumentDO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.version.AiDocVersionDO;
import cn.iocoder.yudao.module.knowledge.dal.mysql.knowledge.AiDocumentMapper;
import cn.iocoder.yudao.module.knowledge.service.version.AiDocVersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 知识运营中心 - 知识链路(Knowledge Ops Document Trace: 文档 → 版本 → 入库任务/阶段 → 片段)
 */
@Tag(name = "管理后台 - 知识运营(知识链路)")
@RestController
@RequestMapping("/knowledge/ops")
@Validated
public class KnowledgeOpsController {

    @Resource
    private AiDocumentMapper aiDocumentMapper;
    @Resource
    private AiDocVersionService aiDocVersionService;
    @Resource
    private IngestionApi ingestionApi;

    @GetMapping("/document-trace")
    @Operation(summary = "文档链路 Trace(文档/版本/入库任务时间轴/片段)")
    @PreAuthorize("@ss.hasPermission('ai:knowledge:query')")
    public CommonResult<Map<String, Object>> documentTrace(@RequestParam("documentId") Long documentId) {
        AiDocumentDO doc = aiDocumentMapper.selectById(documentId);
        if (doc == null) {
            return success(null);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        // 文档业务信息
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", doc.getId());
        document.put("name", doc.getName());
        document.put("type", doc.getType());
        document.put("parseStatus", doc.getParseStatus());
        document.put("chunkCount", doc.getChunkCount());
        document.put("domainMetadata", doc.getDomainMetadata() == null ? null
                : cn.hutool.json.JSONUtil.parseObj(doc.getDomainMetadata()));
        document.put("errorMsg", doc.getErrorMsg());
        result.put("document", document);
        // 版本状态
        AiDocVersionDO version = aiDocVersionService.getLatestVersion(documentId);
        if (version != null) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("versionNo", version.getVersionNo());
            v.put("status", version.getStatus());
            v.put("effectiveFrom", version.getEffectiveFrom());
            v.put("effectiveTo", version.getEffectiveTo());
            result.put("version", v);
        }
        // 入库任务时间轴
        try {
            result.put("jobTrace", ingestionApi.getIngestionJobTrace(documentId).getCheckedData());
        } catch (Exception e) {
            result.put("jobTrace", null);
        }
        // 片段列表(截断内容, 保留元数据)
        List<Map<String, Object>> chunks = new ArrayList<>();
        try {
            List<ChunkRespDTO> chunkList = ingestionApi.getChunksByVersion(version == null ? -1 : version.getId())
                    .getCheckedData();
            if (chunkList != null) {
                for (ChunkRespDTO c : chunkList) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", c.getId());
                    item.put("chunkType", c.getChunkType());
                    item.put("chunkRole", c.getChunkRole());
                    item.put("status", c.getStatus());
                    item.put("metadata", c.getMetadata() == null ? null : cn.hutool.json.JSONUtil.parseObj(c.getMetadata()));
                    item.put("content", StrUtil.sub(c.getContent(), 0, 200));
                    chunks.add(item);
                }
            }
        } catch (Exception e) {
            // 片段获取失败不影响主链路展示
        }
        result.put("chunks", chunks);
        return success(result);
    }
}
