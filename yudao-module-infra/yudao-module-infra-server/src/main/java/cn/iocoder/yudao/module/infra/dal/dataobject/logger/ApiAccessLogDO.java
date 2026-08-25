package cn.iocoder.yudao.module.infra.dal.dataobject.logger;

import cn.iocoder.yudao.framework.apilog.core.enums.OperateTypeEnum;
import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * API 访问日志
 *
 * @author 芋道源码
 */
@TableName("infra_api_access_log")
@KeySequence(value = "infra_api_access_log_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiAccessLogDO extends BaseDO {

    /**
     * {@link #requestParams} 的最大长度
     */
    public static final Integer REQUEST_PARAMS_MAX_LENGTH = 8000;

    /**
     * {@link #responseBody} 的最大字符长度。
     *
     * <p>MySQL infra_api_access_log.response_body 为 TEXT，最大 65535 bytes。
     * 数据库使用 utf8mb4，单字符最坏可占 4 bytes，因此限制为 16000 字符，
     * 为字段本身和编码边界留出安全余量。评估/对话响应包含完整 Trace/Evidence 时尤其需要该保护。</p>
     */
    public static final Integer RESPONSE_BODY_MAX_LENGTH = 16000;

    /**
     * {@link #resultMsg} 的最大长度
     */
    public static final Integer RESULT_MSG_MAX_LENGTH = 512;

    /**
     * 编号
     */
    @TableId
    private Long id;
    /**
     * 链路追踪编号
     *
     * 一般来说，通过链路追踪编号，可以将访问日志，错误日志，链路追踪日志，logger 打印日志等，结合在一起，从而进行排错。
     */
    private String traceId;
    /**
     * 用户编号
     */
    private Long userId;
    /**
     * 用户类型
     *
     * 枚举 {@link UserTypeEnum}
     */
    private Integer userType;
    /**
     * 应用名
     *
     * 目前读取 `spring.application.name` 配置项
     */
    private String applicationName;

    // ========== 请求相关字段 ==========

    /**
     * 请求方法名
     */
    private String requestMethod;
    /**
     * 访问地址
     */
    private String requestUrl;
    /**
     * 请求参数
     *
     * query: Query String
     * body: Quest Body
     */
    private String requestParams;
    /**
     * 响应结果
     */
    private String responseBody;
    /**
     * 用户 IP
     */
    private String userIp;
    /**
     * 浏览器 UA
     */
    private String userAgent;

    // ========== 执行相关字段 ==========

    /**
     * 操作模块
     */
    private String operateModule;
    /**
     * 操作名
     */
    private String operateName;
    /**
     * 操作分类
     *
     * 枚举 {@link OperateTypeEnum}
     */
    private Integer operateType;

    /**
     * 开始请求时间
     */
    private LocalDateTime beginTime;
    /**
     * 结束请求时间
     */
    private LocalDateTime endTime;
    /**
     * 执行时长，单位：毫秒
     */
    private Integer duration;

    /**
     * 结果码
     *
     * 目前使用的 {@link CommonResult#getCode()} 属性
     */
    private Integer resultCode;
    /**
     * 结果提示
     *
     * 目前使用的 {@link CommonResult#getMsg()} 属性
     */
    private String resultMsg;

}
