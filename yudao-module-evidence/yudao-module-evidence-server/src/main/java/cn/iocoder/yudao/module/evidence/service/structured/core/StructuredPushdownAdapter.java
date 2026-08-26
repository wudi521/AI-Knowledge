package cn.iocoder.yudao.module.evidence.service.structured.core;

/**
 * 可选的 Structured 关系计划下推扩展点。
 *
 * <p>不修改现有 StructuredDataAdapter 合同。只有实现本接口的领域 Adapter 才参与下推；
 * 其它 Adapter 保持原有 load -> Core 内存执行行为。</p>
 */
public interface StructuredPushdownAdapter extends StructuredDataAdapter {

    /**
     * 尝试在权威后端完整执行当前结构化计划。
     *
     * @return UNSUPPORTED = Core 回退旧执行路径；SUCCEEDED = 直接采用结果；FAILED = fail-closed。
     */
    StructuredPushdownResult executePushdown(StructuredDataRequest request, StructuredQueryPlan plan);
}
