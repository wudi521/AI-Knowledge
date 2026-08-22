package cn.iocoder.yudao.module.ingestion.split;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 切分产物: 一个知识片段(携带可追溯元数据, 供入库幂等/检索上下文/来源定位)
 */
@Data
public class Chunk {

    /** 内容 */
    private String content;

    /** 类型: SEMANTIC/TABLE/FAQ/POLICY/STRUCTURE */
    private String chunkType;

    /** 父块引用: null=无父(父块/叶子); 否则为父块在切分结果列表中的下标(落库时回填真实 DB id) */
    private Long parentId;

    /** 角色: PARENT/CHILD/LEAF/TABLE/IMAGE(由切分器标注, 未标注时落库按类型推导) */
    private String chunkRole;

    /** 章节路径(标题链, ">" 分隔; 结构切分产生) */
    private String sectionPath;

    /** 来源起始页(1-based; 未知 -1) */
    private int sourcePageStart = -1;

    /** 来源结束页(未知 -1) */
    private int sourcePageEnd = -1;

    /** 元数据(JSON 字符串) */
    private String metadata;

    public Chunk() {
    }

    public Chunk(String content, String chunkType) {
        this.content = content;
        this.chunkType = chunkType;
    }

    public static List<Chunk> of(String content, String chunkType) {
        List<Chunk> list = new ArrayList<>();
        list.add(new Chunk(content, chunkType));
        return list;
    }

}
