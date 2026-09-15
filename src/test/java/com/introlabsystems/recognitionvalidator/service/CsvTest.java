package com.introlabsystems.recognitionvalidator.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CsvTest {
    @Test
    void spreadsheetPrefixesStayTextIncludingWhitespaceAndUnicodeVariants() {
        for (String text : new String[]{"=SUM(1,2)", "+1", "-1", "@x", "  =x", "\t=x", "\r=x", "\n=x",
                "\u00a0=x", "\uFEFF=x", "＝x", "＋x", "＠x", "－x", "\tplain"}) {
            assertThat(Csv.cell(text)).as(text).startsWith("\"'");
        }
        assertThat(Csv.cell(null)).isEqualTo("\"\"");
        assertThat(Csv.cell(false)).isEqualTo("\"false\"");
        assertThat(Csv.cell(0)).isEqualTo("\"0\"");
        assertThat(Csv.cell("a,\"b\"\r\nc")).isEqualTo("\"a,\"\"b\"\"\r\nc\"");
    }
}
