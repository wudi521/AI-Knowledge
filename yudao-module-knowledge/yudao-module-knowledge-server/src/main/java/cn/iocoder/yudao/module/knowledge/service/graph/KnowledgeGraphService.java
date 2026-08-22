package cn.iocoder.yudao.module.knowledge.service.graph;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.entity.AiEntityAliasDO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.entity.AiEntityDO;
import cn.iocoder.yudao.module.knowledge.dal.dataobject.entity.AiRelationDO;
import cn.iocoder.yudao.module.knowledge.dal.mysql.entity.AiEntityAliasMapper;
import cn.iocoder.yudao.module.knowledge.dal.mysql.entity.AiEntityMapper;
import cn.iocoder.yudao.module.knowledge.dal.mysql.entity.AiRelationMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识图谱核心服务(批次 E, MySQL 1~2 hop):
 * 实体注册/别名消歧(小张/张三/张工→同一实体)、SPO 关系(幂等+时间范围)、
 * 合并审计、图遍历(每跳返回实体+谓词+有效期, 供多跳推理与逐跳证据)。
 * 实体/关系抽取(LLM)与冲突 SPO 化比较在后续接入, 本服务提供确定性数据基础。
 */
@Slf4j
@Service
public class KnowledgeGraphService {

    @Resource
    private AiEntityMapper entityMapper;
    @Resource
    private AiEntityAliasMapper aliasMapper;
    @Resource
    private AiRelationMapper relationMapper;

    // ========== 实体消歧注册 ==========

    /**
     * 解析或创建实体(消歧): 别名精确匹配 → 归一化名称匹配 → 规范化名称匹配 → 新建。
     * 低置信不自动合并: 仅精确别名/归一化等价合并, 歧义留给人工 merge。
     *
     * @return 实体 id
     */
    public Long resolveOrCreateEntity(String name, String entityType, String source) {
        if (StrUtil.isBlank(name)) {
            throw new IllegalArgumentException("实体名不能为空");
        }
        String trimmed = name.trim();
        String normalized = normalize(trimmed);
        // 1. 别名精确匹配(小张/张三/张工)
        AiEntityAliasDO alias = aliasMapper.selectByAlias(trimmed);
        if (alias != null) {
            return alias.getEntityId();
        }
        // 2. 归一化名称匹配(大小写/空格等价)
        AiEntityDO byNormalized = entityMapper.selectByNormalizedName(normalized);
        if (byNormalized != null) {
            return byNormalized.getId();
        }
        // 3. 规范化名称精确匹配
        AiEntityDO byCanonical = entityMapper.selectByCanonicalName(trimmed);
        if (byCanonical != null) {
            return byCanonical.getId();
        }
        // 4. 新建(别名=自身)
        AiEntityDO entity = new AiEntityDO();
        entity.setEntityType(StrUtil.isBlank(entityType) ? "GENERIC" : entityType);
        entity.setCanonicalName(trimmed);
        entity.setNormalizedName(normalized);
        entity.setStatus("ACTIVE");
        entity.setConfidence(BigDecimal.ONE);
        entityMapper.insert(entity);
        AiEntityAliasDO selfAlias = new AiEntityAliasDO();
        selfAlias.setEntityId(entity.getId());
        selfAlias.setAlias(trimmed);
        selfAlias.setAliasType("SYNONYM");
        selfAlias.setConfidence(BigDecimal.ONE);
        selfAlias.setSource(StrUtil.isBlank(source) ? "MANUAL" : source);
        aliasMapper.insert(selfAlias);
        return entity.getId();
    }

    /** 给实体添加别名(消歧用; 别名唯一) */
    public Long addAlias(Long entityId, String alias, String aliasType, String source) {
        if (aliasMapper.selectByAlias(alias) != null) {
            return -1L; // 别名已被占用(幂等跳过)
        }
        AiEntityAliasDO a = new AiEntityAliasDO();
        a.setEntityId(entityId);
        a.setAlias(alias);
        a.setAliasType(StrUtil.isBlank(aliasType) ? "SYNONYM" : aliasType);
        a.setConfidence(BigDecimal.ONE);
        a.setSource(StrUtil.isBlank(source) ? "MANUAL" : source);
        aliasMapper.insert(a);
        return a.getId();
    }

    /** 实体合并(可审计/可回滚: 别名与关系转移至目标, 源实体置 MERGED) */
    public void mergeEntities(Long fromId, Long toId, String reason, String operator) {
        if (fromId.equals(toId)) {
            return;
        }
        // 别名转移(冲突跳过)
        for (AiEntityAliasDO alias : aliasMapper.selectByEntityId(fromId)) {
            if (aliasMapper.selectByAlias(alias.getAlias()) == null) {
                alias.setEntityId(toId);
                aliasMapper.updateById(alias);
            }
        }
        // 关系转移: 主体/客体指向 from 的改到 to
        for (AiRelationDO r : relationMapper.selectList(
                new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<AiRelationDO>()
                        .eq(AiRelationDO::getSubjectEntityId, fromId))) {
            r.setSubjectEntityId(toId);
            relationMapper.updateById(r);
        }
        for (AiRelationDO r : relationMapper.selectList(
                new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<AiRelationDO>()
                        .eq(AiRelationDO::getObjectEntityId, fromId))) {
            r.setObjectEntityId(toId);
            relationMapper.updateById(r);
        }
        // 源实体置 MERGED(保留行供审计/回滚)
        AiEntityDO update = new AiEntityDO();
        update.setId(fromId);
        update.setStatus("MERGED");
        entityMapper.updateById(update);
        log.info("[mergeEntities][实体 {} → {} 合并完成, reason={}, operator={}]", fromId, toId, reason, operator);
    }

    // ========== 关系(SPO 幂等) ==========

    /**
     * 创建关系(SPO 幂等: 同主体+谓词+客体/值且 ACTIVE 时跳过; 支持时间范围/权威)
     *
     * @return 关系 id(已存在返回既有 id)
     */
    public Long createRelation(Long subjectEntityId, String predicate, Long objectEntityId, String objectValue,
                               LocalDate validFrom, LocalDate validTo, Integer authority, String source) {
        List<AiRelationDO> exist = relationMapper.selectBySpo(subjectEntityId, predicate, objectEntityId, objectValue);
        if (!exist.isEmpty()) {
            return exist.get(0).getId(); // 幂等
        }
        AiRelationDO relation = new AiRelationDO();
        relation.setSubjectEntityId(subjectEntityId);
        relation.setPredicate(predicate);
        relation.setObjectEntityId(objectEntityId);
        relation.setObjectValue(objectValue);
        relation.setValidFrom(validFrom);
        relation.setValidTo(validTo);
        relation.setAuthority(authority == null ? 0 : authority);
        relation.setConfidence(BigDecimal.valueOf(0.9));
        relation.setSource(StrUtil.isBlank(source) ? "MANUAL" : source);
        relation.setStatus("ACTIVE");
        relationMapper.insert(relation);
        return relation.getId();
    }

    // ========== 图遍历(1~2 hop) ==========

    /** 遍历结果路径(每跳: 主体名 + 谓词 + 客体名/值 + 有效期) */
    public record TraversalPath(List<Hop> hops) {
    }

    public record Hop(String subjectName, String predicate, String objectName, String objectValue,
                      LocalDate validFrom, LocalDate validTo) {
    }

    /**
     * 图遍历(BFS, 最多 maxHops): "小张的上级的上级" → 2-hop 路径列表
     *
     * @param startName 起始实体名(自动消歧)
     * @param predicate 谓词(空=全部)
     * @param maxHops   最大跳数(1~2, 当前 MySQL 实现限制 2)
     */
    public List<TraversalPath> traverse(String startName, String predicate, int maxHops) {
        List<TraversalPath> paths = new ArrayList<>();
        if (StrUtil.isBlank(startName)) {
            return paths;
        }
        Long startId = resolveOrCreateEntity(startName, "GENERIC", "RULE"); // 解析(不存在则创建空壳, 查询无结果)
        AiEntityDO start = entityMapper.selectById(startId);
        if (start == null) {
            return paths;
        }
        int hops = Math.max(1, Math.min(maxHops, 2));
        traverseRecursive(start, predicate, hops, new ArrayList<>(), paths);
        return paths;
    }

    private void traverseRecursive(AiEntityDO current, String predicate, int remainingHops,
                                   List<Hop> path, List<TraversalPath> paths) {
        List<AiRelationDO> outgoing = relationMapper.selectOutgoing(current.getId(), predicate);
        for (AiRelationDO relation : outgoing) {
            String objectName = null;
            if (relation.getObjectEntityId() != null) {
                AiEntityDO object = entityMapper.selectById(relation.getObjectEntityId());
                objectName = object == null ? null : object.getCanonicalName();
            }
            Hop hop = new Hop(current.getCanonicalName(), relation.getPredicate(),
                    objectName, relation.getObjectValue(), relation.getValidFrom(), relation.getValidTo());
            List<Hop> next = new ArrayList<>(path);
            next.add(hop);
            if (remainingHops <= 1 || relation.getObjectEntityId() == null) {
                paths.add(new TraversalPath(next)); // 达到跳数上限或值型终点
                continue;
            }
            AiEntityDO nextEntity = entityMapper.selectById(relation.getObjectEntityId());
            if (nextEntity != null) {
                traverseRecursive(nextEntity, predicate, remainingHops - 1, next, paths);
            }
        }
    }

    /** 归一化: 小写 + 去全部空白 */
    private String normalize(String name) {
        return name.toLowerCase().replaceAll("\\s+", "");
    }

}
