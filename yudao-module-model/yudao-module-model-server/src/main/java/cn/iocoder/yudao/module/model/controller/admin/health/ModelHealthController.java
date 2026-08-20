package cn.iocoder.yudao.module.model.controller.admin.health;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.model.dal.dataobject.model.AiModelConfigDO;
import cn.iocoder.yudao.module.model.dal.mysql.model.AiModelConfigMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 模型网关健康检查")
@RestController
@RequestMapping("/model/health")
public class ModelHealthController {

    @Resource
    private AiModelConfigMapper aiModelConfigMapper;

    @GetMapping("/ping")
    @Operation(summary = "模型网关服务存活")
    public CommonResult<String> ping() {
        return success("pong");
    }

    @GetMapping("/models")
    @Operation(summary = "模型网关: 启用模型可达性探测(TCP 连通)")
    public CommonResult<List<Map<String, Object>>> models() {
        // 全部启用模型(getEnableModelListByType(null) 会拼 type = null 查不到, 故直接用 Mapper)
        List<AiModelConfigDO> configs = aiModelConfigMapper.selectList(new LambdaQueryWrapperX<AiModelConfigDO>()
                .eq(AiModelConfigDO::getStatus, 1)
                .orderByAsc(AiModelConfigDO::getId));
        List<Map<String, Object>> result = new ArrayList<>();
        for (AiModelConfigDO cfg : configs) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", cfg.getId());
            m.put("name", cfg.getName());
            m.put("type", cfg.getType());
            m.put("scenario", cfg.getScenario());
            m.put("modelName", cfg.getModelName());
            m.put("baseUrl", cfg.getBaseUrl());
            m.put("reachable", tcpReachable(cfg.getBaseUrl()));
            result.add(m);
        }
        return success(result);
    }

    /** TCP 连通探测: baseUrl 形如 http://host:port/v1 → 取 host:port; 失败返回 false */
    private boolean tcpReachable(String baseUrl) {
        if (baseUrl == null) {
            return false;
        }
        try {
            java.net.URI uri = new java.net.URI(baseUrl);
            int port = uri.getPort() > 0 ? uri.getPort() : (uri.getScheme().equals("https") ? 443 : 80);
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress(uri.getHost(), port), 2000);
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

}
