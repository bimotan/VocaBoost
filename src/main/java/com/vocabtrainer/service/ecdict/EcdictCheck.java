package com.vocabtrainer.service.ecdict;


import static com.vocabtrainer.util.Messages.tr;

/**
 * What a quick look at the start of a dictionary CSV found; see {@link EcdictImportService#check}.
 *
 * @param format      encoding, delimiter and columns, as in {@link com.vocabtrainer.domain.EcdictMetadata#format()}
 * @param rowsChecked entry rows looked at (the header row not counted)
 * @param usableRows  rows with a word and a Chinese meaning
 * @param sample      the first usable entry, cleaned, such as "abandon: 放弃; 抛弃; ..."; empty when none
 */
public record EcdictCheck(String format, int rowsChecked, int usableRows, String sample) {
    public String toDisplayText() {
        String result = tr("ecdict.check.rows", rowsChecked, usableRows, rowsChecked - usableRows);
        return format + System.lineSeparator() + result
            + (sample.isEmpty() ? "" : System.lineSeparator() + tr("ecdict.check.example", sample));
    }
}
