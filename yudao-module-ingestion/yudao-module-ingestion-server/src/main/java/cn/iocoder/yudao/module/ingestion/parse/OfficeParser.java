package cn.iocoder.yudao.module.ingestion.parse;

import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;

/**
 * Word/Excel/PPT 解析(经 Apache Tika, 内部使用 POI)
 */
@Component
public class OfficeParser implements DocumentParser {

    private final Tika tika = new Tika();

    @Override
    public String parse(String filePath, String docType) throws IOException, TikaException {
        return tika.parseToString(new File(filePath));
    }

}
