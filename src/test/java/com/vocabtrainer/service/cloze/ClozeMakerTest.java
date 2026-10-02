package com.vocabtrainer.service.cloze;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Finding a word, also inflected, in its example sentence (review finding G2; the blanks of G1). */
class ClozeMakerTest {
    private final ClozeMaker rulesOnly = new ClozeMaker(WordForms.NONE);

    @Test
    void theWordItselfIsFoundIgnoringCase() {
        assertEquals(List.of(text("The storm began to "), word("abate"), text(".")),
            rulesOnly.highlight("The storm began to abate.", "abate"));
        assertEquals(List.of(word("Apathy"), text(" can weaken civic life.")),
            rulesOnly.highlight("Apathy can weaken civic life.", "apathy"));
        assertEquals(List.of(text("It was "), word("LUCID"), text(".")), rulesOnly.highlight("It was LUCID.", "Lucid"));
    }

    @Test
    void regularInflectionsAreFound() {
        assertEquals("admonished", target(rulesOnly, "The mentor admonished him to check the data.", "admonish"));
        assertEquals("abating", target(rulesOnly, "The winds are abating.", "abate"));
        assertEquals("rebutted", target(rulesOnly, "The author rebutted the criticism.", "rebut"));
        assertEquals("abetting", target(rulesOnly, "He was accused of abetting the fraud.", "abet"));
        assertEquals("belied", target(rulesOnly, "Her calm voice belied her anxiety.", "belie"));
        assertEquals("decries", target(rulesOnly, "She decries waste.", "decry"));
        assertEquals("vicissitudes", target(rulesOnly, "The company survived many vicissitudes.", "vicissitude"));
        assertEquals("anomalies", target(rulesOnly, "Two anomalies stood out.", "anomaly"));
        assertEquals("laconically", target(rulesOnly, "He answered laconically.", "laconic"));
        assertEquals("subtly", target(rulesOnly, "The tone shifted subtly.", "subtle"));
        assertEquals("capriciously", target(rulesOnly, "The rules changed capriciously.", "capricious"));
        assertEquals("acquitted", target(rulesOnly, "The jury acquitted him.", "acquit"));
        assertEquals("mimicked", target(rulesOnly, "The parrot mimicked her.", "mimic"));
        assertEquals("buttresses", target(rulesOnly, "Data buttresses the claim.", "buttress"));
    }

    @Test
    void irregularFormsComeFromTheDictionary() {
        WordForms ecdict = english -> Map.of(
            "forgo", Set.of("forwent", "forgone", "forgoes", "forgoing"),
            "give", Set.of("gave", "given", "giving", "gives")).getOrDefault(english, Set.of());
        ClozeMaker withDictionary = new ClozeMaker(ecdict);

        assertEquals("forwent", target(withDictionary, "She forwent the bonus.", "forgo"));
        assertEquals(List.of(text("She forwent the bonus.")), rulesOnly.highlight("She forwent the bonus.", "forgo"),
            "an irregular form is not found without the dictionary");
        assertEquals("gave up", target(withDictionary, "He finally gave up the plan.", "give up"));
    }

    @Test
    void onlyWholeWordsAreFound() {
        assertEquals(List.of(text("The abatement was small.")), rulesOnly.highlight("The abatement was small.", "abate"));
        assertEquals(List.of(text("A party of artists.")), rulesOnly.highlight("A party of artists.", "art"));
        assertEquals(List.of(text("The "), word("storm"), text("'s end.")), rulesOnly.highlight("The storm's end.", "storm"),
            "an apostrophe ends the word");
        assertEquals(List.of(text("self-"), word("effacing"), text(" humor")),
            rulesOnly.highlight("self-effacing humor", "efface"), "a hyphen ends a word too");
    }

    @Test
    void expressionsMayInflectTheirFirstOrLastWordAndUseAnySpacing() {
        assertEquals("gives up", target(rulesOnly, "She never gives up.", "give up"));
        assertEquals("red herrings", target(rulesOnly, "The clues were red herrings.", "red herring"));
        assertEquals("ad  hoc", target(rulesOnly, "An ad  hoc committee.", "ad hoc"));
        assertEquals("well being", target(rulesOnly, "It improves well being.", "well-being"));
        assertEquals(List.of(text("Give it up.")), rulesOnly.highlight("Give it up.", "give up"),
            "the words of an expression must be next to each other");
    }

    @Test
    void everyOccurrenceIsFound() {
        assertEquals(List.of(word("Abate"), text(" now, and it will "), word("abate"), text(" later.")),
            rulesOnly.highlight("Abate now, and it will abate later.", "abate"));
    }

    @Test
    void anEmptySentenceHasNoSpansAndAMissingWordOneUntargetedSpan() {
        assertEquals(List.of(), rulesOnly.highlight("", "abate"));
        assertEquals(List.of(), rulesOnly.highlight(null, "abate"));
        assertEquals(List.of(text("The sky is blue.")), rulesOnly.highlight("  The sky is blue. ", "abate"));
        assertEquals(List.of(text("The sky is blue.")), rulesOnly.highlight("The sky is blue.", " "));
        assertEquals(List.of(text("C++ is a language.")), rulesOnly.highlight("C++ is a language.", "C#"),
            "the word is matched literally, not as a pattern");
    }

    @Test
    void aClozeBlanksEveryFormOfTheWordAndKnowsWhatWasBlanked() {
        Cloze cloze = rulesOnly.make("The mentor admonished him, then admonished her too.", "admonish").orElseThrow();

        assertEquals("The mentor _____ him, then _____ her too.", cloze.masked());
        assertEquals("The mentor admonished him, then admonished her too.", cloze.sentence());
        assertEquals(List.of("admonished"), cloze.blankedForms());

        Cloze mixed = rulesOnly.make("Abate it now and it abates later.", "abate").orElseThrow();
        assertEquals("_____ it now and it _____ later.", mixed.masked());
        assertEquals(List.of("Abate", "abates"), mixed.blankedForms());

        WordForms ecdict = english -> english.equals("forgo") ? Set.of("forwent", "forgone") : Set.of();
        Cloze irregular = new ClozeMaker(ecdict).make("She forwent the bonus.", "forgo").orElseThrow();
        assertEquals("She _____ the bonus.", irregular.masked());
        assertEquals(List.of("forwent"), irregular.blankedForms());

        Cloze expression = rulesOnly.make("The clues were red herrings.", "red herring").orElseThrow();
        assertEquals("The clues were _____.", expression.masked());
        assertEquals(List.of("red herrings"), expression.blankedForms());
    }

    @Test
    void noClozeWithoutASentenceTheWordOrAnythingAroundIt() {
        assertTrue(rulesOnly.make(null, "abate").isEmpty());
        assertTrue(rulesOnly.make("  ", "abate").isEmpty());
        assertTrue(rulesOnly.make("The abatement was small.", "abate").isEmpty(), "the word is not in it");
        assertTrue(rulesOnly.make("Abate!", "abate").isEmpty(), "nothing to fill the blank from");
        assertTrue(rulesOnly.make("She forwent the bonus.", "forgo").isEmpty(), "irregular, and no dictionary");
    }

    @Test
    void simpleRulesGiveTheUsualForms() {
        assertTrue(Inflections.of("abate").containsAll(Set.of("abate", "abates", "abated", "abating")));
        assertTrue(Inflections.of("decry").containsAll(Set.of("decries", "decried", "decrying")));
        assertTrue(Inflections.of("happy").contains("happily"));
        assertTrue(Inflections.of("abet").containsAll(Set.of("abetted", "abetting", "abets")));
        assertTrue(Inflections.of("defer").containsAll(Set.of("deferred", "deferring")));
        assertTrue(Inflections.of("quit").contains("quitting"));
        assertTrue(Inflections.of("panic").containsAll(Set.of("panicked", "panicking")));
        assertTrue(Inflections.of("stymie").containsAll(Set.of("stymied", "stymying")));
        assertTrue(Inflections.of("true").contains("truly"));
        assertTrue(Inflections.of("Allow").containsAll(Set.of("allow", "allowed", "allowing")));
        assertTrue(Inflections.of("allow").stream().noneMatch(form -> form.contains("ww")), "w is never doubled");
        assertEquals(Set.of(), Inflections.of(" "));
        assertEquals(Set.of("c#"), Inflections.of("C#"), "no endings after a character that is not a letter");
    }

    private static String target(ClozeMaker maker, String sentence, String english) {
        List<String> targets = maker.highlight(sentence, english).stream()
            .filter(SentenceSpan::target)
            .map(SentenceSpan::text)
            .toList();
        assertEquals(1, targets.size(), "one occurrence of " + english + " in: " + sentence + " " + targets);
        return targets.get(0);
    }

    private static SentenceSpan text(String text) {
        return new SentenceSpan(text, false);
    }

    private static SentenceSpan word(String text) {
        return new SentenceSpan(text, true);
    }
}
