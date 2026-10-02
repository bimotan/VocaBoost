package com.vocabtrainer.service.ecdict;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** ECDICT-shaped CSV files for tests. */
public final class EcdictFixtures {
    public static final String HEADER = "word,phonetic,definition,translation,pos,collins,oxford,tag,bnc,frq,exchange,detail,audio";

    // Real rows of ecdict.csv (github.com/skywind3000/ECDICT), unchanged. "\\n" is ECDICT's literal
    // backslash-n line separator inside a field, not a line break.
    public static final String HOOD =
        "'hood,hʊd,,\"n. 罩；风帽；（布质）面罩；学位连领帽（表示学位种类）\\nv. 覆盖；用头巾包；使(马,鹰等)戴头罩；给…加罩\\n[网络] 胡德；兜帽；引擎盖\",,,,,0,0,,,";
    public static final String A =
        "a,ei,n. the 1st letter of the Roman alphabet\\nn. the blood group whose red cells carry the A antigen,\"第一个字母 A; 一个; 第一的\\r\\nart. [计] 累加器, 加法器, 地址, 振幅, 模拟, 区域, 面积, 汇编, 组件, 异步\",,5,1,zk gk,5,5,,\"\"\"\"\"\",";
    public static final String ABACUS =
        "abacus,'æbәkәs,n. a tablet placed horizontally on top of the capital of a column as an aid in supporting the architrave\\nn. a calculator that performs arithmetic functions by manually sliding counters on rods or in grooves,n. 算盘\\n[计] 算盘,,,,,33592,30850,,,";
    public static final String ABACUSES =
        "abacuses,'æbəkəs,pl.  of Abacus,n. 算盘,,,,,0,0,0:abacuse/1:s,,";
    public static final String ABANDON =
        "abandon,ә'bændәn,\"n. the trait of lacking restraint or control; reckless freedom from inhibition or worry\\nv. forsake, leave behind\\nv. give up with the intent of never claiming again\\nv. stop maintaining or insisting on; of ideas or claims\",\"vt. 放弃, 抛弃, 遗弃, 使屈从, 沉溺, 放纵\\nn. 放任, 无拘束, 狂热\",,3,1,gk cet4 cet6 ky toefl gre,2057,2182,d:abandoned/p:abandoned/i:abandoning/3:abandons,,";
    public static final String ABANDONED =
        "abandoned,ә'bændәnd,s. forsaken by owner or inhabitants\\ns. free from constraint,\"a. 被抛弃的, 无约束的, 恣意放荡的\",,3,1,toefl,9617,6184,0:abandon/1:dp/p:abandoned/d:abandoned,,";
    /** Not in {@link #REAL_ROWS}: an inflected form's own row, whose translation starts with a remark. */
    public static final String ABANDONMENTS =
        "abandonments,,plural of abandonment\\nn. the act of giving something up\\nn. the voluntary surrender of property (or a right to property) without attempting to reclaim it or give it away,\"(abandonment 的复数) n. 放弃, 抛弃, 放纵\\n[经] 委付, 废弃, 放弃(采矿权)\",,,,,0,0,0:abandonment/1:s,,";

    /** The real rows above, in ECDICT's order. */
    public static final List<String> REAL_ROWS = List.of(HOOD, A, ABACUS, ABACUSES, ABANDON, ABANDONED);

    private EcdictFixtures() {
    }

    /** Writes the header and {@code rows} as UTF-8 with CRLF line ends, as ecdict.csv is; optionally with a byte order mark. */
    public static Path write(Path file, boolean byteOrderMark, List<String> rows) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            if (byteOrderMark) {
                writer.write('﻿');
            }
            writer.write(HEADER);
            writer.write("\r\n");
            for (String row : rows) {
                writer.write(row);
                writer.write("\r\n");
            }
        }
        return file;
    }

    /**
     * Writes {@code count} generated entries ({@link #generatedWord}) shaped like ECDICT rows: quoted
     * translations with literal "\n", a tagged line and an English definition. Every fifth entry
     * has inflections ("wfed", "wfing", ...) in its exchange field. Entry {@code i} means
     * "{meaningPrefix}{i}; 测试; 检验".
     */
    public static Path writeGenerated(Path file, int count, String meaningPrefix) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write(HEADER);
            writer.write("\r\n");
            for (int i = 0; i < count; i++) {
                String word = generatedWord(i);
                String exchange = i % 5 == 0 ? "p:" + word + "ed/d:" + word + "ed/i:" + word + "ing/3:" + word + "s" : "";
                writer.write(word + ",'wɜːd" + i + ",\"n. a generated entry\\nv. to test, quickly\",\"n. " + meaningPrefix + i
                    + ", 测试\\nv. 检验\\n[网络] 生成\",,3,1,gre," + (i + 1) + "," + (i + 1) + "," + exchange + ",,\r\n");
            }
        }
        return file;
    }

    /** The word of generated entry {@code i}: "wa", "wb", ..., "wz", "wba", ...; letters only, so the add form accepts it. */
    public static String generatedWord(int i) {
        StringBuilder letters = new StringBuilder();
        for (char c : Integer.toString(i, 26).toCharArray()) {
            letters.append((char) (Character.isDigit(c) ? 'a' + (c - '0') : c + 10));
        }
        return "w" + letters;
    }
}
