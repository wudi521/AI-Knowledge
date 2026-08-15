package cn.iocoder.yudao.module.model.service.model;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.model.controller.admin.model.vo.AiModelConfigPageReqVO;
import cn.iocoder.yudao.module.model.controller.admin.model.vo.AiModelConfigSaveReqVO;
import cn.iocoder.yudao.module.model.dal.dataobject.model.AiModelConfigDO;
import jakarta.validation.Valid;

import java.util.List;

/**
 * 模型配置 Service 接口
 */
public interface AiModelConfigService {

    /** 创建模型配置 */
    Long createAiModelConfig(@Valid AiModelConfigSaveReqVO createReqVO);

    /** 更新模型配置 */
    void updateAiModelConfig(@Valid AiModelConfigSaveReqVO updateReqVO);

    /** 删除模型配置 */
    void deleteAiModelConfig(Long id);

    /** 获得模型配置 */
    AiModelConfigDO getAiModelConfig(Long id);

    /** 获得模型配置分页 */
    PageResult<AiModelConfigDO> getAiModelConfigPage(AiModelConfigPageReqVO pageReqVO);

    /** 获得指定类型的已启用模型列表(供知识库等下拉使用) */
    List<AiModelConfigDO> getEnableModelListByType(String type);

}
