package cn.iocoder.yudao.module.ingestion.domain;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 领域适配器注册表: 收集 Spring 容器全部 DomainIngestionAdapter, 按 domainCode 索引;
 * 未找到领域实现时回退 GENERAL(不阻断入库)。
 */
@Component
public class DomainIngestionRegistry {

    private final Map<String, DomainIngestionAdapter> adapters = new LinkedHashMap<>();

    public DomainIngestionRegistry(List<DomainIngestionAdapter> adapterList) {
        for (DomainIngestionAdapter adapter : adapterList) {
            if (adapter != null && StrUtil.isNotBlank(adapter.domainCode())) {
                adapters.put(adapter.domainCode(), adapter);
            }
        }
    }

    /** 按领域代码取适配器; 空/未知领域回退 GENERAL */
    public DomainIngestionAdapter get(String domainCode) {
        if (StrUtil.isBlank(domainCode)) {
            return adapters.getOrDefault("GENERAL", adapters.values().iterator().next());
        }
        return adapters.getOrDefault(domainCode, adapters.getOrDefault("GENERAL", adapters.values().iterator().next()));
    }
}
