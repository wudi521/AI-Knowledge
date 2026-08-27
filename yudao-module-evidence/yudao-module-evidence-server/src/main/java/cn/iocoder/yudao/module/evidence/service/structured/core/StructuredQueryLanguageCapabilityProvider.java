package cn.iocoder.yudao.module.evidence.service.structured.core;

import java.util.List;

/**
 * Query IR 语言能力发现 SPI。
 *
 * <p>领域插件在运行时声明自己可供 Planner 使用的通用查询语言边界；新增普通自然语言问法
 * 不应新增 Provider 条目，只有物理执行语言/能力边界变化时才需要扩展这里。</p>
 */
public interface StructuredQueryLanguageCapabilityProvider {
    String domainCode();
    List<StructuredQueryLanguageCapability> capabilities();
}
