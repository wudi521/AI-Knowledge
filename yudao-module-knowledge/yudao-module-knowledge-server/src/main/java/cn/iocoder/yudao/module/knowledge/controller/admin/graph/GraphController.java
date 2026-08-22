package cn.iocoder.yudao.module.knowledge.controller.admin.graph;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.knowledge.service.graph.KnowledgeGraphService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 管理后台 - 知识图谱(实体消歧/关系/多跳遍历, 批次 E)
 */
@Tag(name = "管理后台 - 知识图谱")
@RestController
@RequestMapping("/knowledge/graph")
@Validated
public class GraphController {

    @Resource
    private KnowledgeGraphService graphService;

    @PostMapping("/entity-resolve")
    @Operation(summary = "解析/注册实体(自动别名消歧)")
    @PreAuthorize("@ss.hasPermission('knowledge:graph:manage')")
    public CommonResult<Long> resolveEntity(@RequestParam("name") @NotEmpty String name,
                                            @RequestParam(value = "entityType", defaultValue = "GENERIC") String entityType) {
        return success(graphService.resolveOrCreateEntity(name, entityType, "MANUAL"));
    }

    @PostMapping("/alias")
    @Operation(summary = "添加实体别名(消歧)")
    @PreAuthorize("@ss.hasPermission('knowledge:graph:manage')")
    public CommonResult<Long> addAlias(@RequestParam("entityId") @NotNull Long entityId,
                                       @RequestParam("alias") @NotEmpty String alias) {
        return success(graphService.addAlias(entityId, alias, "SYNONYM", "MANUAL"));
    }

    @PostMapping("/relation")
    @Operation(summary = "创建关系(SPO 幂等, 支持时间范围)")
    @PreAuthorize("@ss.hasPermission('knowledge:graph:manage')")
    public CommonResult<Long> createRelation(@RequestParam("subjectEntityId") @NotNull Long subjectEntityId,
                                             @RequestParam("predicate") @NotEmpty String predicate,
                                             @RequestParam(value = "objectEntityId", required = false) Long objectEntityId,
                                             @RequestParam(value = "objectValue", required = false) String objectValue,
                                             @RequestParam(value = "validFrom", required = false) LocalDate validFrom,
                                             @RequestParam(value = "validTo", required = false) LocalDate validTo) {
        return success(graphService.createRelation(subjectEntityId, predicate, objectEntityId, objectValue,
                validFrom, validTo, 0, "MANUAL"));
    }

    @PostMapping("/merge")
    @Operation(summary = "实体合并(别名/关系转移, 可审计回滚)")
    @PreAuthorize("@ss.hasPermission('knowledge:graph:manage')")
    public CommonResult<Boolean> mergeEntities(@RequestParam("fromId") @NotNull Long fromId,
                                               @RequestParam("toId") @NotNull Long toId,
                                               @RequestParam(value = "reason", required = false) String reason) {
        graphService.mergeEntities(fromId, toId, reason, null);
        return success(true);
    }

    @GetMapping("/traverse")
    @Operation(summary = "图遍历(1~2 hop; 如 小张的上级的上级)")
    @PreAuthorize("@ss.hasPermission('knowledge:graph:query')")
    public CommonResult<List<KnowledgeGraphService.TraversalPath>> traverse(
            @RequestParam("start") @NotEmpty String start,
            @RequestParam(value = "predicate", required = false) String predicate,
            @RequestParam(value = "hops", defaultValue = "2") int hops) {
        return success(graphService.traverse(start, predicate, hops));
    }

}
