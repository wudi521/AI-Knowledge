package cn.iocoder.yudao.module.chat.service.evidence;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.evidence.api.EvidenceApi;
import cn.iocoder.yudao.module.evidence.api.dto.ChatTurnDTO;
import cn.iocoder.yudao.module.evidence.api.dto.EvidenceEvaluateReqDTO;
import cn.iocoder.yudao.module.evidence.api.dto.EvidenceEvaluateRespDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 证据评估 RPC 适配器: 对话模块 → evidence-server 证据评估
 * <p>
 * 降级原则(永不抛出): RPC 网络/序列化异常或业务失败(非 0 码、空响应)时, 记录告警日志并返回 null,
 * 由调用方 {@code ChatPipeline} 兜底为转人工("评估服务暂不可用")。
 * Feign 无登录态, 租户/用户显式传递; 传入为空时尝试从安全上下文补齐(本地直连无 token 时透传 null,
 * 由证据侧检索 RPC 自行降级)。
 */
@Slf4j
@Component
public class EvidenceRpcAdapter {

    /** topK 默认值(与证据侧默认一致) */
    private static final int DEFAULT_TOP_K = 8;

    @Resource
    private EvidenceApi evidenceApi;

    /**
     * 证据评估(单轮, 无历史上下文)
     * <p>
     * 向后兼容委托: 历史为空, 行为与旧版本一致。供未接入多轮上下文的调用方使用。
     */
    public EvidenceEvaluateRespDTO evaluate(String query, Long tenantId, Long userId, Integer topK) {
        return evaluate(query, tenantId, userId, topK, null);
    }

    /**
     * 证据评估
     *
     * @param query    评估问题(必填)
     * @param tenantId 租户编号(为空时从登录态补齐)
     * @param userId   用户编号(为空时从登录态补齐)
     * @param topK     证据条数(空则默认 8)
     * @param history  历史上下文轮次(USER/AI, 可为空 = 单轮; SYSTEM 交接消息由调用方排除)
     * @return 评估结果; 任何失败均返回 null, 永不抛出
     */
    public EvidenceEvaluateRespDTO evaluate(String query, Long tenantId, Long userId, Integer topK,
                                            List<ChatTurnDTO> history) {
        // 登录态兜底: 调用方未显式传租户/用户时, 从安全上下文补齐
        LoginUser loginUser = SecurityFrameworkUtils.getLoginUser();
        if (tenantId == null && loginUser != null) {
            tenantId = loginUser.getTenantId();
        }
        if (userId == null && loginUser != null) {
            userId = loginUser.getId();
        }
        EvidenceEvaluateReqDTO req = new EvidenceEvaluateReqDTO();
        req.setQuery(query);
        req.setKbIds(null); // null = 全部可见知识库
        req.setTopK(topK != null ? topK : DEFAULT_TOP_K);
        req.setTenantId(tenantId);
        req.setUserId(userId);
        req.setHistory(history);
        CommonResult<EvidenceEvaluateRespDTO> resp;
        try {
            resp = evidenceApi.evaluate(req);
        } catch (Exception e) {
            // 网络/序列化等异常: 优雅降级
            log.warn("[evaluate][query({}) 调用证据评估 RPC 异常, 降级返回 null]", query, e);
            return null;
        }
        // 业务失败/空响应: 优雅降级(防御 resp 为 null 或 code 为 null 的 NPE 逃逸, 保证永不抛出)
        if (resp == null || resp.getCode() == null || resp.getCode() != 0 || resp.getData() == null) {
            log.warn("[evaluate][query({}) 证据评估 RPC 失败: code({}) msg({}), 降级返回 null]", query,
                    resp != null ? resp.getCode() : null, resp != null ? resp.getMsg() : null);
            return null;
        }
        return resp.getData();
    }

}
