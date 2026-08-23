package cn.iocoder.yudao.module.knowledge.api.dto;

import lombok.Data;

import java.util.List;

/**
 * Structured Query 数据响应(完整结构化数据集)。
 * <p>
 * truncated=true 表示数据源未返回完整集(超过上限), 调用方 Completeness Guard 禁止基于部分 rows
 * 计算 COUNT/SUM/AVG/MIN/MAX 等全集结论。
 */
@Data
public class StructuredQueryRespDTO {

    /** 每对象一行(完整数据集) */
    private List<StructuredQueryRowDTO> rows;

    /** 是否被截断(超过 rowCap) */
    private boolean truncated;

}
