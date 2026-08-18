package cn.iocoder.yudao.module.evidence.service.conflict;

import cn.hutool.core.util.StrUtil;

/**
 * JSON 对象文本提取(纯静态工具, 无外部依赖, 便于单元测试)
 * <p>
 * 兼容 LLM 输出的非严格格式: ```json 代码围栏、前后说明文字、多余空白等。
 */
public final class JsonExtract {

    private JsonExtract() {
    }

    /**
     * 提取首个 "{" 到最后一个 "}" 之间的子串(含边界), 作为 JSON 对象文本。
     *
     * @param text LLM 原始输出(可含代码围栏/前后缀文字)
     * @return JSON 对象文本; 未找到合法的 "{"..."}" 时返回 null
     */
    public static String extractObject(String text) {
        if (StrUtil.isBlank(text)) {
            return null;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

}
