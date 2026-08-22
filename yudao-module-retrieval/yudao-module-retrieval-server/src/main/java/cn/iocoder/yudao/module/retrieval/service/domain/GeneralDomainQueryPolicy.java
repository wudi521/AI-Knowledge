package cn.iocoder.yudao.module.retrieval.service.domain;

import org.springframework.stereotype.Component;

/**
 * 通用领域查询策略: 产品门禁/槽位检测保持现状, 提示词用代码默认
 */
@Component
public class GeneralDomainQueryPolicy implements DomainQueryPolicy {

    @Override
    public String domainCode() {
        return "GENERAL";
    }

    @Override
    public String queryAnalysisPrompt() {
        return null; // 用代码默认(客服)提示词
    }

    @Override
    public boolean enableProductGate() {
        return true;
    }

    @Override
    public boolean enableSlotDetection() {
        return true;
    }
}
