package com.vocabtrainer.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiExplanationTest {
    private static final String NL = System.lineSeparator();

    @Test
    void theRequestedJsonIsShownAsLabelledSections() {
        AiExplanation explanation = AiExplanation.parse("""
            {"meaning": "adj. 清晰易懂的",
             "answer_feedback": "“清楚”基本正确，但 lucid 更强调表达清晰易懂。",
             "memory_tip": "lu- 与 light 同源：照亮 → 清楚明白。",
             "example_en": "Her lucid explanation made the theory easy to follow.",
             "example_zh": "她清晰的解释让这个理论容易理解。"}
            """);

        assertTrue(explanation.isStructured());
        assertEquals("Meaning: adj. 清晰易懂的" + NL
            + "About your answer: “清楚”基本正确，但 lucid 更强调表达清晰易懂。" + NL
            + "Memory tip: lu- 与 light 同源：照亮 → 清楚明白。" + NL
            + "Example: Her lucid explanation made the theory easy to follow." + NL
            + "  她清晰的解释让这个理论容易理解。", explanation.text());
    }

    @Test
    void jsonInACodeFenceAfterThinkingAndChatterIsStillRead() {
        String reply = "<think>The learner typed 放弃, confusing abandon with...</think>\n"
            + "Sure! Here is the explanation:\n```json\n"
            + "{'meaning': '放纵; 放任', \"Answer Feedback\": \"你写的“放弃”是 abandon 的另一个意思。\",\n"
            + " \"mnemonic\": [\"a- + band\", \"挣脱束缚\"], \"example\": {\"en\": \"They danced with abandon.\", \"zh\": \"他们尽情跳舞。\"},}\n"
            + "```";

        AiExplanation explanation = AiExplanation.parse(reply);

        assertEquals("放纵; 放任", explanation.meaning());
        assertEquals("你写的“放弃”是 abandon 的另一个意思。", explanation.answerFeedback());
        assertEquals("a- + band; 挣脱束缚", explanation.memoryTip());
        assertEquals("They danced with abandon.", explanation.example());
        assertEquals("他们尽情跳舞。", explanation.exampleTranslation());
        assertFalse(explanation.text().contains("think"), explanation.text());
    }

    @Test
    void emptySectionsAreLeftOut() {
        AiExplanation explanation = AiExplanation.parse(
            "{\"meaning\": \"清晰的\", \"answer_feedback\": \"\", \"memory_tip\": null, \"example_en\": \"A lucid essay.\"}");

        assertEquals("Meaning: 清晰的" + NL + "Example: A lucid essay.", explanation.text());
    }

    @Test
    void aReplyThatIsNotTheRequestedJsonIsShownAsItIs() {
        String markdown = "**lucid** 清晰的。\n记忆：light → 明亮 → 清楚。";
        assertEquals(markdown, AiExplanation.parse(markdown).text());
        assertFalse(AiExplanation.parse(markdown).isStructured());

        String broken = "{\"meaning\": \"清晰的\", \"example_en\": \"unterminated}";
        assertEquals(broken, AiExplanation.parse(broken).text());

        String otherJson = "{\"word\": \"lucid\", \"level\": 3}";
        assertEquals(otherJson, AiExplanation.parse(otherJson).text(), "JSON without any section is shown as it is");

        assertEquals("", AiExplanation.parse(null).text());
    }
}
