package com.vocabtrainer.ui.review;

import com.vocabtrainer.app.AppServices;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.TestDatabases;
import com.vocabtrainer.service.AiService;
import com.vocabtrainer.service.AutoExplain;
import com.vocabtrainer.service.ExplanationRequest;
import com.vocabtrainer.ui.DataChanges;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When a checked answer is explained (review finding G8): by itself after every answer, only after a
 * mistake (the default) or never, and otherwise with Explain. The offline mock text, which sends
 * nothing, is shown at once whatever the setting.
 */
class ReviewExplainPresenterTest {
    @TempDir
    Path tempDir;

    @RegisterExtension
    final TestDatabases databases = new TestDatabases();

    private final ReviewSessionPresenterTest.ManualTasks tasks = new ReviewSessionPresenterTest.ManualTasks();
    private final RecordingAi ai = new RecordingAi();
    private final AtomicReference<AutoExplain> setting = new AtomicReference<>(AutoExplain.AFTER_MISTAKE);
    private ReviewSessionPresenter presenter;
    private long deckId;

    @BeforeEach
    void openDatabase() throws SQLException {
        AppServices services = AppServices.builder(tempDir.resolve("vocab.db")).open();
        databases.track(services.databaseManager());
        presenter = new ReviewSessionPresenter(services.reviewService(), services.goalService(), () -> ai, tasks,
            new DataChanges(), (title, error) -> { });
        presenter.setAutoExplain(setting::get);
        deckId = services.startupDeck().getId();
        presenter.showDeck(deckId);
    }

    @Test
    void afterAMistakeOnlyAnswersTheCheckCappedAreExplainedByThemselves() {
        assertTrue(presenter.offersExplain(), "Explain is offered, disabled, before the answer");
        assertFalse(presenter.canExplain());
        WordCard right = presenter.card().orElseThrow();
        presenter.setAnswer(firstMeaning(right));

        presenter.submit();
        tasks.runAll();

        assertEquals(List.of(), ai.requests, "a right answer is not sent");
        assertFalse(presenter.result().contains("AI explanation"), presenter.result());
        assertTrue(presenter.result().startsWith("Correct answer: " + right.getChinese()), presenter.result());
        assertTrue(presenter.canExplain());
        assertTrue(presenter.offersExplain());
        assertFalse(presenter.canRegenerateExplanation(), "nothing to regenerate yet");

        presenter.explain();
        assertTrue(presenter.result().endsWith("AI explanation: loading..."), presenter.result());
        assertFalse(presenter.canExplain(), "asked once");
        tasks.runAll();

        assertEquals(1, ai.requests.size());
        assertEquals(right.getEnglish(), ai.requests.get(0).word().getEnglish());
        assertTrue(presenter.result().endsWith("Explanation of " + right.getEnglish()), presenter.result());
        assertFalse(presenter.offersExplain(), "Regenerate takes its place");
        assertTrue(presenter.canRegenerateExplanation());

        presenter.rate(ReviewRating.GOOD);
        WordCard wrong = presenter.card().orElseThrow();
        assertTrue(presenter.offersExplain(), "the next card has no explanation yet");
        presenter.setAnswer("完全错误");
        presenter.submit();
        assertTrue(presenter.result().endsWith("AI explanation: loading..."), "a wrong answer is explained by itself");
        tasks.runAll();

        assertEquals(2, ai.requests.size());
        assertEquals(wrong.getEnglish(), ai.requests.get(1).word().getEnglish());
        assertEquals("完全错误", ai.requests.get(1).typedAnswer());
        assertFalse(presenter.canExplain());
        assertTrue(presenter.canRegenerateExplanation());
    }

    @Test
    void alwaysExplainsEveryAnswerAndNeverOnlyWhenAsked() {
        setting.set(AutoExplain.ALWAYS);
        WordCard right = presenter.card().orElseThrow();
        presenter.setAnswer(firstMeaning(right));
        presenter.submit();
        tasks.runAll();
        assertEquals(1, ai.requests.size(), "a right answer is explained too");

        setting.set(AutoExplain.ON_DEMAND);
        presenter.rate(ReviewRating.GOOD);
        presenter.setAnswer("完全错误");
        presenter.submit();
        tasks.runAll();

        assertEquals(1, ai.requests.size(), "not even a wrong answer is sent");
        assertTrue(presenter.canExplain());
        presenter.explain();
        tasks.runAll();
        assertEquals(2, ai.requests.size());
    }

    @Test
    void theOfflineMockIsShownAtOnceWhateverTheSetting() {
        setting.set(AutoExplain.ON_DEMAND);
        ai.available = false;
        WordCard right = presenter.card().orElseThrow();
        presenter.setAnswer(firstMeaning(right));

        presenter.submit();
        tasks.runAll();

        assertEquals(1, ai.requests.size(), "the mock sends nothing, so it is shown");
        assertTrue(presenter.result().endsWith("Explanation of " + right.getEnglish()), presenter.result());
        assertFalse(presenter.offersExplain());
        assertFalse(presenter.canExplain());
        assertFalse(presenter.canRegenerateExplanation());
    }

    @Test
    void aSettingThatCannotBeReadCountsAsTheDefault() {
        presenter.setAutoExplain(() -> {
            throw new IllegalStateException("Cannot read setting: ai.autoExplain");
        });
        WordCard right = presenter.card().orElseThrow();
        presenter.setAnswer(firstMeaning(right));
        presenter.submit();
        tasks.runAll();
        assertEquals(List.of(), ai.requests);

        presenter.rate(ReviewRating.GOOD);
        presenter.setAnswer("完全错误");
        presenter.submit();
        tasks.runAll();
        assertEquals(1, ai.requests.size(), "after a mistake, as by default");
    }

    @Test
    void anExplanationAskedForACardIsNotShownOnTheNext() {
        WordCard right = presenter.card().orElseThrow();
        presenter.setAnswer(firstMeaning(right));
        presenter.submit();
        presenter.explain();
        presenter.rate(ReviewRating.GOOD);

        tasks.runAll();

        assertFalse(presenter.result().contains("Explanation of"), presenter.result());
        assertTrue(presenter.offersExplain());
    }

    private static String firstMeaning(WordCard word) {
        return word.getChinese().split("[;；,，/、]")[0].trim();
    }

    /** Records the requests; while not available it stands for the offline mock text. */
    static final class RecordingAi implements AiService {
        final List<ExplanationRequest> requests = new ArrayList<>();
        boolean available = true;

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public String explain(WordCard word) {
            return explain(ExplanationRequest.of(word));
        }

        @Override
        public String explain(ExplanationRequest request) {
            requests.add(request);
            return "Explanation of " + request.word().getEnglish();
        }
    }
}
