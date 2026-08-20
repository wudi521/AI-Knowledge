package cn.iocoder.yudao.module.model.service.gateway;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.model.dal.dataobject.model.AiModelConfigDO;
import cn.iocoder.yudao.module.model.dal.mysql.model.AiModelConfigMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 路由解析: (租户,类型,场景) → 启用模型候选列表(priority 升序)
 * 先精确场景, 再回退 '*' 默认场景; 均无 → 空列表(网关回退 yaml)
 * 租户过滤由 TenantBaseDO 框架自动附加
 */
@Component
public class ModelResolver {

    @Resource
    private AiModelConfigMapper aiModelConfigMapper;

    public List<AiModelConfigDO> resolveCandidates(String type, String scenario) {
        List<AiModelConfigDO> exact = aiModelConfigMapper.selectList(new LambdaQueryWrapperX<AiModelConfigDO>()
                .eq(AiModelConfigDO::getType, type)
                .eq(AiModelConfigDO::getScenario, scenario == null ? "*" : scenario)
                .eq(AiModelConfigDO::getStatus, CommonStatusEnum.ENABLE.getStatus())
                .orderByAsc(AiModelConfigDO::getPriority)
                .orderByAsc(AiModelConfigDO::getId));
        if (!exact.isEmpty()) {
            return exact;
        }
        return aiModelConfigMapper.selectList(new LambdaQueryWrapperX<AiModelConfigDO>()
                .eq(AiModelConfigDO::getType, type)
                .eq(AiModelConfigDO::getScenario, "*")
                .eq(AiModelConfigDO::getStatus, CommonStatusEnum.ENABLE.getStatus())
                .orderByAsc(AiModelConfigDO::getPriority)
                .orderByAsc(AiModelConfigDO::getId));
    }
}
