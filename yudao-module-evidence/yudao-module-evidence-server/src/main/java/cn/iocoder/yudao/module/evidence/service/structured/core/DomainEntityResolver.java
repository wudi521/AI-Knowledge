package cn.iocoder.yudao.module.evidence.service.structured.core;

import java.util.List;

/**
 * Domain Entity Resolver SPI(Platform Core 消解 "这个/这些/三个/它们/上述/前面的/刚才那几份/其中" 时,
 * 委托 Domain Pack 从文本/历史中抽取并定位对象)。
 * <p>
 * 标识格式(申请号/公布号/合同编号等)是领域相关的, 故抽取逻辑归 Domain Pack; Core 只做通用编排。
 */
public interface DomainEntityResolver {

    /** 领域编码(如 PATENT) */
    String domainCode();

    /**
     * 从一段文本中抽取实体标识(如 申请号/公布号)。
     *
     * @return 命中标识列表(保序); 未命中返回空列表
     */
    List<ResolvedEntity> extractEntities(String text);

    /**
     * 将实体标识定位到具体对象(如 专利文档 documentId)。
     *
     * @param entities extractEntities 的产出
     * @param kbId    宿主知识库(权限已在调用方裁剪)
     * @return 定位成功列表(按实体名去重); 定位失败项丢弃
     */
    List<ResolvedEntity> resolveToEntities(List<ResolvedEntity> entities, Long kbId);

    /** 已定位实体 */
    record ResolvedEntity(String identifier, Long entityId, String entityName) {
    }
}
