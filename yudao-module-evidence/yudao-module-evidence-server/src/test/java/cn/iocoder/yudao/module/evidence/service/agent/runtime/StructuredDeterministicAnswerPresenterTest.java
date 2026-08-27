package cn.iocoder.yudao.module.evidence.service.agent.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredDeterministicAnswerPresenterTest {

    @Test
    void presentsSingleGroupAsDirectConclusion() {
        String answer = StructuredDeterministicAnswerPresenter.present(
                "找出发明人数量最多的专利",
                "分组结果：\n1. 一种体外经颅式治疗仪：4");

        assertThat(answer).isEqualTo("找出发明人数量最多的专利：\n一种体外经颅式治疗仪（4）");
        assertThat(answer).doesNotContain("分组结果");
    }

    @Test
    void mergesExplodedRowsByEntityAndField() {
        String raw = "筛选条件【标题=一种体外经颅式治疗仪 或 标题=一种代替印花的运动服】已命中。当前范围返回 5 个结果：\n"
                + "1. 一种代替印花的运动服：标题=一种代替印花的运动服；发明人=孙新玲\n"
                + "2. 一种体外经颅式治疗仪：标题=一种体外经颅式治疗仪；发明人=郝海涛\n"
                + "3. 一种体外经颅式治疗仪：标题=一种体外经颅式治疗仪；发明人=吴恒莉\n"
                + "4. 一种体外经颅式治疗仪：标题=一种体外经颅式治疗仪；发明人=贾少微\n"
                + "5. 一种体外经颅式治疗仪：标题=一种体外经颅式治疗仪；发明人=何昕";

        String answer = StructuredDeterministicAnswerPresenter.present("罗列专利名字和发明人", raw);

        assertThat(answer).contains("罗列专利名字和发明人：")
                .contains("1. 一种代替印花的运动服：标题=一种代替印花的运动服；发明人=孙新玲")
                .contains("2. 一种体外经颅式治疗仪：标题=一种体外经颅式治疗仪；发明人=郝海涛、吴恒莉、贾少微、何昕");
        assertThat(answer).doesNotContain("筛选条件").doesNotContain("5 个结果");
    }

    @Test
    void turnsAuthoritativeEmptyIntoUserFacingFact() {
        String answer = StructuredDeterministicAnswerPresenter.present(
                "获取两个目标对象的详情",
                "筛选条件【申请号=A 且 申请号=B】未命中任何已发布对象。");

        assertThat(answer).isEqualTo("获取两个目标对象的详情：\n未找到符合条件的结果。");
    }
}
