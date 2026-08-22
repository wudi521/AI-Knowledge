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

    /** API Key 脱敏占位(RespVO 脱敏格式含 *, 提交该值表示"不修改") */
    private static final String API_KEY_MASK = "****";

    @Resource
    private AiModelConfigMapper aiModelConfigMapper;
    @Resource
    private cn.iocoder.yudao.module.model.service.secret.SecretCryptoService secretCryptoService;

    @Override
    @LogRecord(type = MODEL_CONFIG_TYPE, subType = MODEL_CONFIG_CREATE_SUB_TYPE, bizNo = "{{#configId}}",
            success = MODEL_CONFIG_CREATE_SUCCESS)
    public Long createAiModelConfig(AiModelConfigSaveReqVO createReqVO) {
        AiModelConfigDO config = BeanUtils.toBean(createReqVO, AiModelConfigDO.class);
        // A2 密钥加密: 新写只写密文, 明文不落库
        encryptApiKeyIfPresent(config, createReqVO.getApiKey());
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
        // 更新: API Key 为脱敏占位(*)时表示不修改, 保留原密文
        if (cn.hutool.core.util.StrUtil.contains(updateReqVO.getApiKey(), '*')) {
            updateReqVO.setApiKey(null);
        }
        AiModelConfigDO updateObj = BeanUtils.toBean(updateReqVO, AiModelConfigDO.class);
        encryptApiKeyIfPresent(updateObj, updateReqVO.getApiKey());
        aiModelConfigMapper.updateById(updateObj);
    }

    /**
     * 遗留明文迁移: 把 apiKey 明文(非空)且无密文的记录加密写密文并清空明文。
     * 受保护的管理操作(需要应用层主密钥, SQL 无法完成), 幂等可重复执行。
     *
     * @return 处理的记录数
     */
    @Override
    public int encryptLegacyApiKeys() {
        java.util.List<AiModelConfigDO> legacy = aiModelConfigMapper.selectList(
                new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<AiModelConfigDO>()
                        .isNotNull(AiModelConfigDO::getApiKey)
                        .apply("api_key <> ''")
                        .isNull(AiModelConfigDO::getApiKeyCipher));
        int count = 0;
        for (AiModelConfigDO cfg : legacy) {
            encryptApiKeyIfPresent(cfg, cfg.getApiKey());
            cfg.setApiKey(null); // 迁移后清空明文
            aiModelConfigMapper.updateById(cfg);
            count++;
        }
        return count;
    }

    /** 若提供明文 apiKey(非空白), 加密写密文字段并清空明文字段 */
    private void encryptApiKeyIfPresent(AiModelConfigDO config, String plainApiKey) {
        if (config == null || cn.hutool.core.util.StrUtil.isBlank(plainApiKey)) {
            return;
        }
        cn.iocoder.yudao.module.model.service.secret.SecretCryptoService.Encrypted enc =
                secretCryptoService.encrypt(plainApiKey.trim());
        if (enc != null) {
            config.setApiKeyCipher(enc.ciphertext());
            config.setApiKeyNonce(enc.nonce());
            config.setApiKeyKeyVersion(enc.keyVersion());
            config.setApiKey(null); // 新写只写密文
        }
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
