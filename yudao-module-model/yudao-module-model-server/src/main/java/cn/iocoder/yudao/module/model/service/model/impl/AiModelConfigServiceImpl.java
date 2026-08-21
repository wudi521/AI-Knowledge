package cn.iocoder.yudao.module.model.service.model.impl;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.model.controller.admin.model.vo.AiModelConfigPageReqVO;
import cn.iocoder.yudao.module.model.controller.admin.model.vo.AiModelConfigSaveReqVO;
import cn.iocoder.yudao.module.model.dal.dataobject.model.AiModelConfigDO;
import cn.iocoder.yudao.module.model.dal.mysql.model.AiModelConfigMapper;
import cn.iocoder.yudao.module.model.service.model.AiModelConfigService;
import com.mzt.logapi.context.LogRecordContext;
import com.mzt.logapi.starter.annotation.LogRecord;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.model.enums.ErrorCodeConstants.MODEL_CONFIG_NOT_EXISTS;
import static cn.iocoder.yudao.module.model.enums.ModelLogRecordConstants.*;

/**
 * 模型配置 Service 实现
 */
@Service
@Validated
public class AiModelConfigServiceImpl implements AiModelConfigService {

    @Resource
    private AiModelConfigMapper aiModelConfigMapper;

    @Override
    @LogRecord(type = MODEL_CONFIG_TYPE, subType = MODEL_CONFIG_CREATE_SUB_TYPE, bizNo = "{{#configId}}",
            success = MODEL_CONFIG_CREATE_SUCCESS)
    public Long createAiModelConfig(AiModelConfigSaveReqVO createReqVO) {
        AiModelConfigDO config = BeanUtils.toBean(createReqVO, AiModelConfigDO.class);
        aiModelConfigMapper.insert(config);
        LogRecordContext.putVariable("configId", config.getId());
        return config.getId();
    }

    @Override
    @LogRecord(type = MODEL_CONFIG_TYPE, subType = MODEL_CONFIG_UPDATE_SUB_TYPE, bizNo = "{{#updateReqVO.id}}",
            success = MODEL_CONFIG_UPDATE_SUCCESS)
    public void updateAiModelConfig(AiModelConfigSaveReqVO updateReqVO) {
        // 校验存在
        validateAiModelConfigExists(updateReqVO.getId());
        // 更新
        AiModelConfigDO updateObj = BeanUtils.toBean(updateReqVO, AiModelConfigDO.class);
        aiModelConfigMapper.updateById(updateObj);
    }

    @Override
    @LogRecord(type = MODEL_CONFIG_TYPE, subType = MODEL_CONFIG_DELETE_SUB_TYPE, bizNo = "{{#id}}",
            success = MODEL_CONFIG_DELETE_SUCCESS)
    public void deleteAiModelConfig(Long id) {
        // 校验存在
        AiModelConfigDO config = validateAiModelConfigExists(id);
        LogRecordContext.putVariable("config", config);
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

    private AiModelConfigDO validateAiModelConfigExists(Long id) {
        AiModelConfigDO config = aiModelConfigMapper.selectById(id);
        if (config == null) {
            throw exception(MODEL_CONFIG_NOT_EXISTS);
        }
        return config;
    }

}
