package cn.iocoder.yudao.module.ingestion.parse;

import cn.hutool.core.io.FileUtil;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * TXT / MD 纯文本解析
 */
@Component
public class TextParser implements DocumentParser {

    @Override
    public String parse(String filePath, String docType) {
        return FileUtil.readString(filePath, StandardCharsets.UTF_8);
    }

}
