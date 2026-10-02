package com.vocabtrainer.service.ecdict;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * ECDICT's exchange field: the inflections of a base form ("p:abandoned/d:abandoned/i:abandoning/3:abandons")
 * or, for a row that is itself a form, its base form and how it was inflected ("0:abandon/1:p").
 */
public final class EcdictExchange {
    /**
     * The codes of inflections: past tense, past participle, present participle, third person
     * singular, comparative, superlative and plural.
     */
    private static final String INFLECTION_CODES = "pdi3rts";

    private EcdictExchange() {
    }

    /** One {@code code:value} item; items without a code or a value are left out. */
    public record Item(String code, String value) {
        /** Whether the value is an inflection of the row's word, rather than its base form ("0") or how it was inflected ("1"). */
        public boolean isInflection() {
            return code.length() == 1 && INFLECTION_CODES.indexOf(code.charAt(0)) >= 0;
        }
    }

    /** The items of {@code exchange}, in order; empty for an empty field. */
    public static List<Item> parse(String exchange) {
        List<Item> items = new ArrayList<>();
        if (exchange == null || exchange.isBlank()) {
            return items;
        }
        for (String item : exchange.split("/")) {
            int colon = item.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String code = item.substring(0, colon).strip();
            String value = item.substring(colon + 1).strip();
            if (!value.isEmpty()) {
                items.add(new Item(code, value));
            }
        }
        return items;
    }

    /** The inflections the field lists for {@code word}, without the word itself. */
    public static Set<String> inflections(String word, String exchange) {
        Set<String> forms = new LinkedHashSet<>();
        for (Item item : parse(exchange)) {
            if (item.isInflection() && !item.value().equalsIgnoreCase(word)) {
                forms.add(item.value());
            }
        }
        return forms;
    }
}
