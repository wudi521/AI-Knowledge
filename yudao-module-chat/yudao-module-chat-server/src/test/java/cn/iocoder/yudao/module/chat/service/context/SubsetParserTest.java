package cn.iocoder.yudao.module.chat.service.context;

import cn.iocoder.yudao.module.chat.service.context.model.SubsetExpression;
import cn.iocoder.yudao.module.chat.service.context.model.SubsetExpression.Type;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SubsetParserTest {

    @Test
    void parsesOrdinalIndex() {
        assertThat(SubsetParser.parse("第二个的申请人")).extracting(SubsetExpression::getType)
                .isEqualTo(Type.INDEX);
        assertThat(SubsetParser.parse("第二个的申请人").getIndex()).isEqualTo(2);
        assertThat(SubsetParser.parse("第一个专利").getIndex()).isEqualTo(1);
    }

    @Test
    void parsesFirstLast() {
        assertThat(SubsetParser.parse("前两个专利").getType()).isEqualTo(Type.FIRST_N);
        assertThat(SubsetParser.parse("前两个专利").getCount()).isEqualTo(2);
        assertThat(SubsetParser.parse("后三个呢").getType()).isEqualTo(Type.LAST_N);
        assertThat(SubsetParser.parse("后三个呢").getCount()).isEqualTo(3);
        assertThat(SubsetParser.parse("最后一个").getType()).isEqualTo(Type.LAST_N);
    }

    @Test
    void parsesExclude() {
        assertThat(SubsetParser.parse("除了第一个其它几个公布号").getType()).isEqualTo(Type.EXCLUDE_INDEX);
        assertThat(SubsetParser.parse("除了第一个其它几个公布号").getIndex()).isEqualTo(1);
        assertThat(SubsetParser.parse("除了前两个呢").getType()).isEqualTo(Type.EXCLUDE_FIRST_N);
        assertThat(SubsetParser.parse("除了前两个呢").getCount()).isEqualTo(2);
    }

    @Test
    void parsesCardinalityAndPronoun() {
        assertThat(SubsetParser.parse("这三个专利").getType()).isEqualTo(Type.CARDINALITY);
        assertThat(SubsetParser.parse("这三个专利").getCount()).isEqualTo(3);
        assertThat(SubsetParser.parse("它们分别有多少项权利要求？").getType()).isEqualTo(Type.ALL);
        assertThat(SubsetParser.parse("上述几个公布号").getType()).isEqualTo(Type.ALL);
    }

    @Test
    void returnsNullWhenNoReference() {
        assertThat(SubsetParser.parse("当前知识库有多少个专利？")).isNull();
        assertThat(SubsetParser.parse("")).isNull();
    }

    @Test
    void toNumberHandlesChinese() {
        assertThat(SubsetParser.toNumber("一")).isEqualTo(1);
        assertThat(SubsetParser.toNumber("十")).isEqualTo(10);
        assertThat(SubsetParser.toNumber("十二")).isEqualTo(12);
        assertThat(SubsetParser.toNumber("二十")).isEqualTo(20);
        assertThat(SubsetParser.toNumber("4")).isEqualTo(4);
    }

    @Test
    void applySubsets() {
        List<Long> ids = List.of(10L, 20L, 30L, 40L);
        assertThat(SubsetExpression.builder().type(Type.FIRST_N).count(2).build().apply(ids))
                .containsExactly(10L, 20L);
        assertThat(SubsetExpression.builder().type(Type.LAST_N).count(2).build().apply(ids))
                .containsExactly(30L, 40L);
        assertThat(SubsetExpression.builder().type(Type.INDEX).index(2).build().apply(ids))
                .containsExactly(20L); // 第 2 个(1-based) → 0-based 1
        assertThat(SubsetExpression.builder().type(Type.EXCLUDE_INDEX).index(1).build().apply(ids))
                .containsExactly(20L, 30L, 40L); // 排除第 1 个(0-based 0)
        assertThat(SubsetExpression.builder().type(Type.EXCLUDE_FIRST_N).count(1).build().apply(ids))
                .containsExactly(20L, 30L, 40L);
        // CARDINALITY 数量不匹配 → null(触发 CLARIFY)
        assertThat(SubsetExpression.builder().type(Type.CARDINALITY).count(3).build().apply(ids)).isNull();
        assertThat(SubsetExpression.builder().type(Type.CARDINALITY).count(4).build().apply(ids))
                .containsExactly(10L, 20L, 30L, 40L);
    }

}
