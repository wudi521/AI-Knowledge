package cn.iocoder.yudao.module.knowledge.api.dto;

import lombok.Data;

import java.util.List;

/**
 * Structured Query 数据响应。
 *
 * <p>单页接口里 truncated=true 表示当前页后仍有数据，调用方必须继续使用 nextDocumentId
 * 拉取后续页，直到 truncated=false，才能把结果标记为 completeDataset=true。</p>
 */
@Data
public class StructuredQueryRespDTO {

    /** 当前页的结构化对象行。 */
    private List<StructuredQueryRowDTO> rows;

    /** 当前页之后是否仍有数据。 */
    private boolean truncated;

    /** keyset 下一页游标；truncated=true 时必须非空且严格前进。 */
    private Long nextDocumentId;

}
