package cn.iocoder.yudao.module.rule.service.rule;

import org.kie.api.KieBase;
import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.builder.KieModule;
import org.kie.api.builder.Message;
import org.kie.api.runtime.KieContainer;
import org.springframework.stereotype.Component;

/**
 * DRL 规则编译器: 文本 → KieBase(保存时试编译校验 / 运行期构建)
 * <p>
 * 编译失败抛 {@link IllegalArgumentException}(携带 drools 错误明细), 由调用方决定报错或降级
 */
@Component
public class RuleCompiler {

    /**
     * 编译 DRL 文本为 KieBase
     *
     * @param ruleKey 业务键(仅用于生成 DRL 资源路径, 需清洗)
     * @param drl     DRL 规则文本
     * @return KieBase
     * @throws IllegalArgumentException DRL 编译失败(含 drools 错误信息)
     */
    public KieBase compile(String ruleKey, String drl) {
        KieServices kieServices = KieServices.Factory.get();
        KieFileSystem kfs = kieServices.newKieFileSystem();
        kfs.write("src/main/resources/rules/" + sanitize(ruleKey) + ".drl", drl);
        KieBuilder builder = kieServices.newKieBuilder(kfs).buildAll();
        if (builder.getResults().hasMessages(Message.Level.ERROR)) {
            throw new IllegalArgumentException("DRL 编译失败: " + builder.getResults().getMessages(Message.Level.ERROR));
        }
        KieModule module = builder.getKieModule();
        KieContainer container = kieServices.newKieContainer(module.getReleaseId());
        return container.getKieBase();
    }

    private String sanitize(String key) {
        return key == null ? "rule" : key.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

}
