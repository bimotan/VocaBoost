package com.vocabtrainer.domain;

/**
 * One entry of the imported ECDICT dictionary, stored as the CSV has it. The translation keeps
 * ECDICT's literal "\n" line separators, part-of-speech markers and domain tags; lookups clean it
 * (see {@code EcdictTranslationCleaner}). Numbers are null when the CSV leaves them empty.
 *
 * @param pos      ECDICT's part-of-speech distribution such as "v:46/n:54", or a word list's POS column
 * @param exchange inflections such as "p:abandoned/d:abandoned/i:abandoning/3:abandons", or "0:abandon/1:p"
 *                 for a row that is itself an inflected form
 * @param example  an example sentence; only word lists have one, ECDICT does not
 */
public record EcdictRow(
    String word,
    String phonetic,
    String definition,
    String translation,
    String pos,
    Integer collins,
    Integer oxford,
    String tag,
    Integer bnc,
    Integer frq,
    String exchange,
    String example
) {
}
