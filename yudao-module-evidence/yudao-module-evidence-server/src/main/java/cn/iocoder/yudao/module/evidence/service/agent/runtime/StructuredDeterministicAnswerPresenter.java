package cn.iocoder.yudao.module.evidence.service.agent.runtime;

import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 把 structured_query 的确定性文本从“执行器日志口吻”整理成用户可读事实。
 *
 * <p>这里只重排、合并 Tool 已经返回的字符串，不新增计算、不猜测字段含义，也不解释业务 intent。
 * exploded 多行按实体和字段去重合并；单行 GROUP 结果使用节点 purpose 作为结论标题。</p>
 */
final class StructuredDeterministicAnswerPresenter {
    private static final String ROW_RESULT_HEADING = "查询结果";

    private StructuredDeterministicAnswerPresenter() {
    }

    static String present(String purpose, String raw) {
        if (StrUtil.isBlank(raw)) return raw;
        String text = raw.replace("\r\n", "\n").replace('\r', '\n').trim();

        if (text.contains("未命中任何已发布对象") || text.contains("没有符合条件的已发布对象")) {
            return heading(purpose) + "：\n未找到符合条件的结果。";
        }

        int groupMarker = text.indexOf("分组结果：");
        if (groupMarker >= 0) {
            String body = text.substring(groupMarker + "分组结果：".length()).trim();
            List<SimpleEntry> entries = simpleEntries(body);
            if (entries.size() == 1) {
                SimpleEntry entry = entries.get(0);
                return heading(purpose) + "：\n" + entry.label() + "（" + entry.value() + "）";
            }
            if (!entries.isEmpty()) {
                StringBuilder sb = new StringBuilder(heading(purpose)).append("：\n");
                int index = 1;
                for (SimpleEntry entry : entries) {
                    sb.append(index++).append(". ").append(entry.label()).append("：")
                            .append(entry.value()).append('\n');
                }
                return sb.toString().trim();
            }
        }

        int rowsMarker = text.indexOf("个结果：");
        if (rowsMarker >= 0) {
            String body = text.substring(rowsMarker + "个结果：".length()).trim();
            List<EntityRow> rows = entityRows(body);
            if (!rows.isEmpty()) return renderEntityRows(ROW_RESULT_HEADING, rows);
        }

        if (text.startsWith("当前范围共 ") && text.contains("个对象")) {
            int newline = text.indexOf('\n');
            if (newline >= 0) {
                List<EntityRow> rows = entityRows(text.substring(newline + 1));
                if (!rows.isEmpty()) return renderEntityRows(ROW_RESULT_HEADING, rows);
            }
        }

        return StrUtil.isBlank(purpose) ? text : heading(purpose) + "：" + text;
    }

    private static String renderEntityRows(String purpose, List<EntityRow> rows) {
        Map<String, LinkedHashMap<String, LinkedHashSet<String>>> grouped = new LinkedHashMap<>();
        for (EntityRow row : rows) {
            LinkedHashMap<String, LinkedHashSet<String>> fields = grouped.computeIfAbsent(
                    row.entityName(), ignored -> new LinkedHashMap<>());
            for (Map.Entry<String, String> field : row.fields().entrySet()) {
                if (StrUtil.isBlank(field.getValue())) continue;
                fields.computeIfAbsent(field.getKey(), ignored -> new LinkedHashSet<>()).add(field.getValue());
            }
        }

        StringBuilder sb = new StringBuilder(heading(purpose)).append("：\n");
        int index = 1;
        for (Map.Entry<String, LinkedHashMap<String, LinkedHashSet<String>>> entity : grouped.entrySet()) {
            sb.append(index++).append(". ").append(entity.getKey());
            List<String> fields = new ArrayList<>();
            for (Map.Entry<String, LinkedHashSet<String>> field : entity.getValue().entrySet()) {
                // entityName 本身已经展示过；如果某个投影字段与它完全相同，不再机械重复一遍。
                LinkedHashSet<String> values = new LinkedHashSet<>(field.getValue());
                values.remove(entity.getKey());
                if (!values.isEmpty()) fields.add(field.getKey() + "=" + String.join("、", values));
            }
            if (!fields.isEmpty()) sb.append("：").append(String.join("；", fields));
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private static List<SimpleEntry> simpleEntries(String body) {
        List<SimpleEntry> out = new ArrayList<>();
        for (String line : body.split("\n")) {
            String value = stripListPrefix(line);
            if (StrUtil.isBlank(value)) continue;
            int separator = value.indexOf('：');
            if (separator <= 0 || separator >= value.length() - 1) continue;
            out.add(new SimpleEntry(value.substring(0, separator).trim(), value.substring(separator + 1).trim()));
        }
        return List.copyOf(out);
    }

    private static List<EntityRow> entityRows(String body) {
        List<EntityRow> out = new ArrayList<>();
        for (String line : body.split("\n")) {
            String value = stripListPrefix(line);
            if (StrUtil.isBlank(value)) continue;
            int separator = value.indexOf('：');
            String entity = separator < 0 ? value.trim() : value.substring(0, separator).trim();
            if (StrUtil.isBlank(entity)) continue;
            Map<String, String> fields = new LinkedHashMap<>();
            if (separator >= 0 && separator < value.length() - 1) {
                String detail = value.substring(separator + 1);
                for (String pair : detail.split("；")) {
                    int equals = pair.indexOf('=');
                    if (equals <= 0) continue;
                    String key = pair.substring(0, equals).trim();
                    String fieldValue = pair.substring(equals + 1).trim();
                    if (StrUtil.isNotBlank(key)) fields.put(key, fieldValue);
                }
            }
            out.add(new EntityRow(entity, fields));
        }
        return List.copyOf(out);
    }

    private static String stripListPrefix(String raw) {
        String line = raw == null ? "" : raw.trim();
        int dot = line.indexOf(". ");
        if (dot <= 0) return line;
        for (int i = 0; i < dot; i++) {
            if (!Character.isDigit(line.charAt(i))) return line;
        }
        return line.substring(dot + 2).trim();
    }

    private static String heading(String purpose) {
        String value = StrUtil.blankToDefault(purpose, "查询结果").trim();
        while (value.endsWith("：") || value.endsWith(":") || value.endsWith("。")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        return StrUtil.blankToDefault(value, "查询结果");
    }

    private record SimpleEntry(String label, String value) {
    }

    private record EntityRow(String entityName, Map<String, String> fields) {
    }
}
