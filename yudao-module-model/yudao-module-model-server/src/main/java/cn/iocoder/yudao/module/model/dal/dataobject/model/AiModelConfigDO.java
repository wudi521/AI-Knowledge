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
    /** 供应商: OLLAMA / OPENAI / ALIYUN / XINFERENCE */
    private String provider;
    /** 模型标识 */
    private String modelName;
    /** 服务地址 */
    private String baseUrl;
    /** API 密钥 */
    private String apiKey;
    /** 向量维度(embedding 类型用) */
    private Integer dimensions;
    /** 状态: 0 停用 1 启用 */
    private Integer status;
    /** 备注 */
    private String remark;

}
