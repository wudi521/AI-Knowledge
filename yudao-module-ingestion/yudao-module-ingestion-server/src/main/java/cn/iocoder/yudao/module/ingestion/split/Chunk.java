package cn.iocoder.yudao.module.ingestion.split;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 切分产物: 一个知识片段
 */
@Data
public class Chunk {

    /** 内容 */
    private String content;

    /** 类型: SEMANTIC/TABLE/FAQ/POLICY */
    private String chunkType;

    /** 父块编号(仅 ParentChild 策略的子块有值) */
    private Long parentId;

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
