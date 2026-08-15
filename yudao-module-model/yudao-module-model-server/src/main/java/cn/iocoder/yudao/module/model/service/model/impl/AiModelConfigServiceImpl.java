package cn.iocoder.yudao.module.model.service.model.impl;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.model.controller.admin.model.vo.AiModelConfigPageReqVO;
import cn.iocoder.yudao.module.model.controller.admin.model.vo.AiModelConfigSaveReqVO;
import cn.iocoder.yudao.module.model.dal.dataobject.model.AiModelConfigDO;
import cn.iocoder.yudao.module.model.dal.mysql.model.AiModelConfigMapper;
import cn.iocoder.yudao.module.model.service.model.AiModelConfigService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.model.enums.ErrorCodeConstants.MODEL_CONFIG_NOT_EXISTS;

/**
 * 模型配置 Service 实现
 */
@Service
@Validated
public class AiModelConfigServiceImpl implements AiModelConfigService {

    @Resource
    private AiModelConfigMapper aiModelConfigMapper;

    @Override
    public Long createAiModelConfig(AiModelConfigSaveReqVO createReqVO) {
        AiModelConfigDO config = BeanUtils.toBean(createReqVO, AiModelConfigDO.class);
        aiModelConfigMapper.insert(config);
        return config.getId();
    }

    @Override
    public void updateAiModelConfig(AiModelConfigSaveReqVO updateReqVO) {
        // 校验存在
        validateAiModelConfigExists(updateReqVO.getId());
        // 更新
        AiModelConfigDO updateObj = BeanUtils.toBean(updateReqVO, AiModelConfigDO.class);
        aiModelConfigMapper.updateById(updateObj);
    }

    @Override
    public void deleteAiModelConfig(Long id) {
        // 校验存在
        validateAiModelConfigExists(id);
        // 删除
        aiModelConfigMapper.deleteById(id);
    }

    @Override
    public AiModelConfigDO getAiModelConfig(Long id) {
        return aiModelConfigMapper.selectById(id);
    }

    @Override
    public PageResult<AiModelConfigDO> getAiModelConfigPage(AiModelConfigPageReqVO pageReqVO) {
        return aiModelConfigMapper.selectPage(pageReqVO);
    }

    @Override
    public List<AiModelConfigDO> getEnableModelListByType(String type) {
        return aiModelConfigMapper.selectList(new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<AiModelConfigDO>()
                .eq(AiModelConfigDO::getType, type)
                .eq(AiModelConfigDO::getStatus, 1)
                .orderByAsc(AiModelConfigDO::getId));
    }

    private void validateAiModelConfigExists(Long id) {
        if (aiModelConfigMapper.selectById(id) == null) {
            throw exception(MODEL_CONFIG_NOT_EXISTS);
        }
    }

}
