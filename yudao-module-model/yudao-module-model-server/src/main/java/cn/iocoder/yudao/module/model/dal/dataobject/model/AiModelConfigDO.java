package cn.iocoder.yudao.module.model.dal.dataobject.model;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 模型配置 DO
 */
@TableName("ai_model_config")
@KeySequence("ai_model_config_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiModelConfigDO extends BaseDO {

    /** 编号 */
    @TableId
    private Long id;
    /** 名称 */
    private String name;
    /** 类型: chat / embedding / rerank */
    private String type;
    /** 场景标识(如 A/B; *=默认场景) */
    private String scenario;
    /** 降级顺序(同类型同场景内, 小者优先) */
    private Integer priority;
    /** 供应商: OLLAMA / OPENAI / ALIYUN / XINFERENCE */
    private String provider;
    /** 模型标识 */
    private String modelName;
    /** 服务地址 */
    private String baseUrl;
    /** API 密钥(旧明文兼容字段; 新写只写密文, 迁移后置空) */
    private String apiKey;
    /** API Key 密文(base64; AES-256-GCM, 见 SecretCryptoService) */
    private String apiKeyCipher;
    /** API Key 加密 nonce(base64) */
    private String apiKeyNonce;
    /** API Key 密钥版本(轮换兼容) */
    private Integer apiKeyKeyVersion;
    /** 向量维度(embedding 类型用) */
    private Integer dimensions;
    /** 输入单价(每百万 token, 元; 成本估算用, 未配置不估金额) */
    private java.math.BigDecimal inPerMtok;
    /** 输出单价(每百万 token, 元) */
    private java.math.BigDecimal outPerMtok;
    /** 状态: 0 停用 1 启用 */
    private Integer status;
    /** 备注 */
    private String remark;

}
