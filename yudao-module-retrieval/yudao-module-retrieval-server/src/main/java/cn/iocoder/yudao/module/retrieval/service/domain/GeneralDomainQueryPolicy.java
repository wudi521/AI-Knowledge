package cn.iocoder.yudao.module.retrieval.service.domain;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 通用领域查询策略: 产品门禁/槽位检测保持现状, 提示词用代码默认; 动态意图走 KB 客服式总结
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
    public List<String> supportedIntents() {
        return null; // 走 KB 动态意图或代码默认
    }

    @Override
    public boolean enableProductGate() {
        return true;
    }

    @Override
    public boolean enableSlotDetection() {
        return true;
    }

    @Override
    public boolean enableAutoIntentSummary() {
        return true; // 客服式意图自动总结(合同条款/收费等)
    }
}
