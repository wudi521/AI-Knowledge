package cn.iocoder.yudao.module.ingestion.split;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 切分策略注解: 标注在 {@link ChunkSplitter} 实现类上, 由 SplitterFactory 构造时自动注册。
 * 新增策略 = 新增一个实现类 + 本注解, 无需改工厂/入口代码。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ChunkStrategy {

    /** 策略唯一标识(如 "structure"; 前端下拉/文档 chunk_strategy 存此值) */
    String key();

    /** 中文名(如 "结构切分") */
    String name();

    /** 适用场景说明 */
    String description() default "";

    /** 默认单块上限 token */
    int maxTokens() default 500;

    /** 默认句子级重叠数 */
    int overlap() default 0;
}
