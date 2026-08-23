package cn.iocoder.yudao.module.retrieval.service.domain.patent;

import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 专利查询预解析器(确定性规则, 不依赖 LLM):
 * 从原始问题提取申请号/公布号/权利要求号/章节提示, 供检索路由(EXACT/SCOPED/HYBRID)与结构化过滤使用。
 * <p>
 * 原则: 能用确定性程序拿到的东西, 不交给大模型猜。
 */
public class PatentQueryPreParser {

    /** 申请号: 12~13 位数字 + 可选 .1~.9 校验位(202311344028.2 / 202311042981.1) */
    private static final Pattern APPLICATION_NO = Pattern.compile("(?<!\\d)(20\\d{10}\\.?\\d?)(?!\\d)");

    /** 公布号: CN + 7~12 位数字 + 可选字母(CN 122621758 A / CN122604134A) */
    private static final Pattern PUBLICATION_NO = Pattern.compile("(?i)(?<!\\w)(CN\\s?\\d{7,12}\\s?[A-Z]?)(?!\\w)");

    /** 权利要求范围: 权利要求1至7 / 权利要求1-7 / 权利要求1到7 */
    private static final Pattern CLAIM_RANGE = Pattern.compile("权利要求\\s*(\\d+)\\s*(?:至|到|~|\\-|—|－)\\s*(\\d+)");

    /** 权利要求列表: 权利要求1、3、5 / 权利要求1,3,5 / 权利要求1和3 */
    private static final Pattern CLAIM_LIST = Pattern.compile("权利要求\\s*(\\d+(?:[、,，和及\\s]+\\d+)+)");

    /** 单个权利要求: 权利要求8 / 第8项权利要求 / 权利要求 8 */
    private static final Pattern CLAIM_SINGLE = Pattern.compile("权利要求\\s*[#第]?\\s*(\\d+)");

    /** 章节提示词 */
    private static final Pattern SECTION_CLAIMS = Pattern.compile("权利要求");
    private static final Pattern SECTION_ABSTRACT = Pattern.compile("摘要");
    private static final Pattern SECTION_DESCRIPTION = Pattern.compile("说明书|具体实施方式|实施例");
    private static final Pattern SECTION_BACKGROUND = Pattern.compile("背景技术");
    private static final Pattern SECTION_DRAWING = Pattern.compile("附图说明|附图");
    private static final Pattern SECTION_TECHNICAL = Pattern.compile("技术领域");

    /**
     * 解析专利查询结构
     *
     * @param query 原始问题
     * @return 解析结果(未命中任何编号时各字段为空, 调用方据此走 HYBRID_RAG)
     */
    public PatentQuerySpec parse(String query) {
        PatentQuerySpec spec = new PatentQuerySpec();
        if (StrUtil.isBlank(query)) {
            return spec;
        }
        // 1. 申请号(优先: 唯一性强)
        Matcher appMatcher = APPLICATION_NO.matcher(query);
        if (appMatcher.find()) {
            spec.applicationNo = normalizeApplicationNo(appMatcher.group(1));
        }
        // 2. 公布号
        Matcher pubMatcher = PUBLICATION_NO.matcher(query);
        if (pubMatcher.find()) {
            spec.publicationNo = normalizePublicationNo(pubMatcher.group(1));
        }
        // 3. 权利要求号(范围 > 列表 > 单个, 均可能命中)
        Matcher range = CLAIM_RANGE.matcher(query);
        if (range.find()) {
            int from = Integer.parseInt(range.group(1));
            int to = Integer.parseInt(range.group(2));
            for (int i = Math.min(from, to); i <= Math.max(from, to); i++) {
                spec.claimNos.add(i);
            }
        } else {
            Matcher list = CLAIM_LIST.matcher(query);
            if (list.find()) {
                for (String part : list.group(1).split("[、,，和及\\s]+")) {
                    if (StrUtil.isNotBlank(part) && part.chars().allMatch(Character::isDigit)) {
                        spec.claimNos.add(Integer.parseInt(part));
                    }
                }
            } else {
                Matcher single = CLAIM_SINGLE.matcher(query);
                while (single.find()) {
                    spec.claimNos.add(Integer.parseInt(single.group(1)));
                }
            }
        }
        // 4. 章节提示(命中即标记, 供路由/过滤)
        if (SECTION_CLAIMS.matcher(query).find()) {
            spec.sectionHint = "CLAIMS";
        } else if (SECTION_ABSTRACT.matcher(query).find()) {
            spec.sectionHint = "ABSTRACT";
        } else if (SECTION_BACKGROUND.matcher(query).find()) {
            spec.sectionHint = "BACKGROUND";
        } else if (SECTION_DRAWING.matcher(query).find()) {
            spec.sectionHint = "DRAWING";
        } else if (SECTION_TECHNICAL.matcher(query).find()) {
            spec.sectionHint = "TECHNICAL_FIELD";
        } else if (SECTION_DESCRIPTION.matcher(query).find()) {
            spec.sectionHint = "DESCRIPTION";
        }
        return spec;
    }

    /** 申请号归一化: 去掉空格, 保留小数点(202311344028.2) */
    private String normalizeApplicationNo(String raw) {
        return raw.replaceAll("\\s+", "");
    }

    /** 公布号归一化: 大写 + 去空格(CN122621758A) */
    private String normalizePublicationNo(String raw) {
        return raw.replaceAll("\\s+", "").toUpperCase();
    }

    /** 专利查询结构化结果 */
    public static class PatentQuerySpec {
        /** 申请号(如 202311344028.2; 未命中 null) */
        private String applicationNo;
        /** 公布号(如 CN122621758A; 未命中 null) */
        private String publicationNo;
        /** 权利要求号集合(未命中空) */
        private final Set<Integer> claimNos = new LinkedHashSet<>();
        /** 章节提示(CLAIMS/ABSTRACT/BACKGROUND/DRAWING/TECHNICAL_FIELD/DESCRIPTION; 未命中 null) */
        private String sectionHint;

        public String getApplicationNo() {
            return applicationNo;
        }

        public String getPublicationNo() {
            return publicationNo;
        }

        public Set<Integer> getClaimNos() {
            return claimNos;
        }

        public List<Integer> getClaimNoList() {
            return new ArrayList<>(claimNos);
        }

        public String getSectionHint() {
            return sectionHint;
        }

        /** 是否命中精确标识(申请号或公布号)——可走 EXACT/SCOPED 路由 */
        public boolean hasExactIdentifier() {
            return StrUtil.isNotBlank(applicationNo) || StrUtil.isNotBlank(publicationNo);
        }

        /** 是否命中精确权利要求(有权利要求号且(有编号或章节=CLAIMS))——可走 EXACT_CLAIM */
        public boolean hasExactClaim() {
            return !claimNos.isEmpty() && (hasExactIdentifier() || "CLAIMS".equals(sectionHint));
        }

        @Override
        public String toString() {
            return "PatentQuerySpec{applicationNo=" + applicationNo + ", publicationNo=" + publicationNo
                    + ", claimNos=" + claimNos + ", sectionHint=" + sectionHint + '}';
        }
    }
}
