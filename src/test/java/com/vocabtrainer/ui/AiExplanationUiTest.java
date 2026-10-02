package com.vocabtrainer.ui;

import com.vocabtrainer.app.AppServices;
import com.vocabtrainer.domain.ReviewLog;
import com.vocabtrainer.domain.ReviewMode;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.service.AiService;
import com.vocabtrainer.service.CachingAiService;
import com.vocabtrainer.service.ExplanationRequest;
import com.vocabtrainer.service.FallbackAiService;
import com.vocabtrainer.service.MockAiService;
import org.junit.jupiter.api.Tag;
import javafx.scene.control.ButtonBase;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AI explanations on the Review tab and the AI settings, with a provider that never uses the network. */
@Tag("ui")
class AiExplanationUiTest extends MainWindowUiTest {
    private final RecordingProvider provider = new RecordingProvider();

    @Override
    AppServices.Builder configure(AppServices.Builder builder) {
        if (testName().startsWith("withoutAProvider")) {
            return builder;
        }
        // The app's composition: the provider behind the cache, with the mock as the fallback.
        return builder.aiService((cache, settings) ->
            new FallbackAiService(new CachingAiService(provider, cache, "test-provider"), new MockAiService()));
    }

    @Test
    void theExplanationIsAskedForTheTypedAnswerAndCanBeRegenerated() throws Exception {
        selectTab("reviewTab");
        WordCard word = questionWord();
        assertTrue(isDisabled("regenerateExplanationButton"), "nothing to regenerate before an answer");

        type("answerField", "完全错误");
        click("submitAnswerButton");
        waitForBackgroundTasks();

        assertEquals(1, provider.requests.size());
        ExplanationRequest request = provider.requests.get(0);
        assertEquals(word.getEnglish(), request.word().getEnglish());
        assertEquals("完全错误", request.typedAnswer());
        assertEquals(ReviewMode.EN_TO_ZH, request.direction());
        assertTrue(text("reviewResultArea").endsWith("Explanation 1 of " + word.getEnglish() + " for 完全错误"),
            text("reviewResultArea"));

        click("regenerateExplanationButton");
        waitForBackgroundTasks();

        assertEquals(2, provider.requests.size(), "regenerate asks the provider although the answer is cached");
        assertTrue(text("reviewResultArea").startsWith("Correct answer: " + word.getChinese()), text("reviewResultArea"));
        assertTrue(text("reviewResultArea").endsWith("Explanation 2 of " + word.getEnglish() + " for 完全错误"),
            text("reviewResultArea"));
        assertFalse(isDisabled("regenerateExplanationButton"));

        click("rateAgainButton");
        assertTrue(isDisabled("regenerateExplanationButton"), "the next card has no answer yet");
    }

    @Test
    void afterRegenerateTheKeyboardStaysOnTheSuggestedRating() throws Exception {
        selectTab("reviewTab");
        WordCard word = questionWord();
        // A right answer, so the suggested rating is Good rather than Again.
        type("answerField", correctAnswer(word));
        click("submitAnswerButton");
        waitForBackgroundTasks();
        assertEquals("rateGoodButton", focusOwnerId());

        // A mouse click focuses the button, which is then disabled while the provider answers.
        provider.hold = new CountDownLatch(1);
        Fx.run(() -> {
            ButtonBase button = find("regenerateExplanationButton", ButtonBase.class);
            button.requestFocus();
            button.fire();
        });
        Fx.flush();
        try {
            assertTrue(isDisabled("regenerateExplanationButton"), "disabled while the provider answers");
            assertEquals("rateGoodButton", focusOwnerId(), "Space must not land on Again");
        } finally {
            provider.hold.countDown();
        }
        waitForBackgroundTasks();
        pressKey(null, KeyCode.SPACE);

        List<ReviewLog> logs = services.reviewLogRepository().findByDeck(currentDeck().getId());
        assertEquals(1, logs.size());
        assertEquals(word.getId(), logs.get(0).getWordId());
        assertEquals(ReviewRating.GOOD, logs.get(0).getRating());
    }

    @Test
    void afterRegeneratingAWrongAnswerTheKeyboardStaysOnItsSuggestedRating() throws Exception {
        selectTab("reviewTab");
        type("answerField", "完全错误");
        click("submitAnswerButton");
        waitForBackgroundTasks();
        // The answer check caps a wrong answer, so the suggestion is Again, not Good.
        assertEquals("rateAgainButton", focusOwnerId());

        Fx.run(() -> {
            ButtonBase button = find("regenerateExplanationButton", ButtonBase.class);
            button.requestFocus();
            button.fire();
        });
        waitForBackgroundTasks();

        assertEquals("rateAgainButton", focusOwnerId(), "the keyboard stays on the suggestion, as after Submit");
    }

    @Test
    void clearingTheAiCacheMakesTheProviderExplainAgain() throws Exception {
        selectTab("reviewTab");
        type("answerField", "完全错误");
        click("submitAnswerButton");
        waitForBackgroundTasks();
        assertEquals(1, provider.requests.size());

        selectTab("settingsTab");
        dialogs.confirm(true);
        click("clearAiCacheButton");

        assertEquals("Cleared 1 cached AI explanation.", text("aiStatusLabel"));
        assertTrue(dialogs.wasShown(ScriptedDialogs.Kind.CONFIRM));
    }

    @Test
    void withoutAProviderTheRegenerateButtonIsHidden() {
        selectTab("reviewTab");
        type("answerField", "完全错误");
        click("submitAnswerButton");
        waitForBackgroundTasks();

        assertTrue(text("reviewResultArea").contains("Mock AI："), text("reviewResultArea"));
        assertFalse(isVisible("regenerateExplanationButton"));
    }

    @Test
    void anInvalidTemperatureIsRefusedAndAValidOneSaved() {
        selectTab("settingsTab");
        type("aiBaseUrlField", "https://api.example.com/v1");
        type("aiApiKeyField", "sk-test-0123456789abcdef");
        type("aiModelField", "model-a");
        type("aiTemperatureField", "3");
        click("saveAiButton");

        assertEquals("Temperature must be a number from 0 to 2, or empty for the provider's default.",
            text("aiStatusLabel"));
        assertTrue(services.settingsService().getAiBaseUrl().isEmpty(), "nothing is saved");

        type("aiTemperatureField", "0.5");
        click("saveAiButton");

        assertEquals(0.5, services.settingsService().getAiTemperature().orElseThrow());
        assertTrue(text("aiStatusLabel").startsWith("Saved."), text("aiStatusLabel"));
    }

    /** Answers every request with a numbered text naming the word and the typed answer. */
    static final class RecordingProvider implements AiService {
        final List<ExplanationRequest> requests = new CopyOnWriteArrayList<>();
        /** When set, a request waits for it, like a slow network call. */
        volatile CountDownLatch hold;

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String explain(WordCard word) {
            return explain(ExplanationRequest.of(word));
        }

        @Override
        public String explain(ExplanationRequest request) {
            CountDownLatch waitFor = hold;
            if (waitFor != null) {
                try {
                    waitFor.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            requests.add(request);
            return "Explanation " + requests.size() + " of " + request.word().getEnglish() + " for " + request.typedAnswer();
        }
    }
}
