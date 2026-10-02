package com.vocabtrainer.ui;

import com.vocabtrainer.app.AppServices;
import com.vocabtrainer.app.VocabTrainerApp;
import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.DictionaryEntry;
import com.vocabtrainer.domain.DictionaryLookupResult;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.service.CompositeDictionaryService;
import com.vocabtrainer.service.DictionaryService;
import com.vocabtrainer.service.LocalDictionaryService;
import com.vocabtrainer.service.MockAiService;
import com.vocabtrainer.service.SettingsService;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.chart.XYChart;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.Labeled;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.image.PixelFormat;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Opens the real main window on a fresh database for each test, wired by {@link AppServices} like
 * the app, with {@link ScriptedDialogs} instead of blocking dialogs and with dictionary and AI
 * services that never use the network. Controls are found by the ids MainWindow gives them.
 *
 * <p>Dashboard, Decks, Statistics and Word List recompute their content only while their tab is
 * shown, like a user would see them, so select the tab before reading its controls.
 */
@Tag("ui")
abstract class MainWindowUiTest {
    static final String STARTER_DECK = "默认词库";
    static final int STARTER_WORDS = 215;
    static final Path SNAPSHOT_DIR = Path.of("target", "ui-snapshots");

    private static final String BACKGROUND_THREAD_NAME = "vocaboost-background-task";
    /** Longer than the Word List's 250 ms search debounce. */
    private static final long INPUT_SETTLE_NANOS = 400_000_000L;

    @TempDir
    Path tempDir;

    final ScriptedDialogs dialogs = new ScriptedDialogs();
    AppServices services;
    private Stage stage;
    private String testName;
    private volatile long lastInputNanos;

    @BeforeAll
    static void startJavaFx() throws InterruptedException {
        Fx.start();
    }

    @BeforeEach
    void openMainWindow(TestInfo testInfo) {
        testName = testInfo.getTestMethod().map(Method::getName).orElse("test");
        lastInputNanos = System.nanoTime() - INPUT_SETTLE_NANOS;
        Fx.drainUncaught();
        AppServices.Builder builder = AppServices.builder(tempDir.resolve("vocab.db"))
            .dictionaryService((cache, settings) -> offlineDictionary(settings))
            .aiService((cache, settings) -> new MockAiService());
        AppServices.Builder configured = configure(builder);
        // Like VocabTrainerApp.start, the services and the window are created on the FX thread.
        Fx.run(() -> {
            try {
                services = configured.open();
            } catch (Exception e) {
                throw new IllegalStateException("Cannot open the test database", e);
            }
            stage = new Stage();
            VocabTrainerApp.showMainWindow(stage, services.createMainWindow(dialogs));
        });
    }

    /** Override to replace part of the wiring, for example with a repository that fails on demand. */
    AppServices.Builder configure(AppServices.Builder builder) {
        return builder;
    }

    @AfterEach
    void closeMainWindow() throws InterruptedException {
        try {
            // A debounced refresh after the last keystroke must still find the database.
            long settle = INPUT_SETTLE_NANOS - (System.nanoTime() - lastInputNanos);
            if (settle > 0) {
                Thread.sleep(settle / 1_000_000L + 1);
            }
            waitForBackgroundTasks();
        } finally {
            Fx.run(() -> {
                if (stage != null) {
                    stage.close();
                }
            });
        }
        List<Throwable> uncaught = Fx.drainUncaught();
        if (!uncaught.isEmpty()) {
            AssertionError error = new AssertionError("Uncaught exception(s) on the JavaFX thread: " + uncaught);
            uncaught.forEach(error::addSuppressed);
            throw error;
        }
        dialogs.verifyFinished();
    }

    /**
     * The app's local dictionary chain (saved ECDICT CSV, then the bundled starter words) followed by
     * a few extra test words, in place of the online dictionaries.
     */
    static DictionaryService offlineDictionary(SettingsService settings) {
        return new CompositeDictionaryService(List.of(
            new LocalDictionaryService(settings.getEcdictPath().orElse(null)),
            new TestDictionary()
        ));
    }

    /** Knows words that are not in the starter deck, so adding them is verified without the network. */
    static final class TestDictionary implements DictionaryService {
        static final String SOURCE = "Test dictionary";
        private static final Map<String, DictionaryEntry> ENTRIES = Map.of(
            "obfuscate", new DictionaryEntry("obfuscate", "使模糊; 使困惑", "verb", "/ˈɒbfʌskeɪt/",
                "Jargon can obfuscate a simple idea.", SOURCE)
        );

        @Override
        public DictionaryLookupResult lookup(String english) {
            DictionaryEntry entry = ENTRIES.get(english == null ? "" : english.trim().toLowerCase(Locale.ROOT));
            return entry == null
                ? DictionaryLookupResult.failure("词条未找到：测试词典没有该词条。")
                : DictionaryLookupResult.success("Loaded from the test dictionary.", List.of(entry));
        }

        @Override
        public boolean isConfigured() {
            return true;
        }
    }

    // ---- Finding controls (call these on the FX thread, e.g. inside Fx.call) ----

    /**
     * The one node with this id anywhere in the window, including tabs that are not selected. Only
     * the nodes MainWindow builds are searched, not the internals of control skins.
     */
    <T> T find(String id, Class<T> type) {
        List<Node> matches = new ArrayList<>();
        for (Node node : allNodes(stage.getScene().getRoot())) {
            if (id.equals(node.getId())) {
                matches.add(node);
            }
        }
        if (matches.size() != 1) {
            throw new AssertionError("Expected one node with id '" + id + "' but found " + matches.size());
        }
        Node node = matches.get(0);
        if (!type.isInstance(node)) {
            throw new AssertionError("#" + id + " is a " + node.getClass().getSimpleName() + ", not a " + type.getSimpleName());
        }
        return type.cast(node);
    }

    /** The id of every node MainWindow built that has one, duplicates included. */
    List<String> allIds() {
        return Fx.call(() -> allNodes(stage.getScene().getRoot()).stream()
            .map(Node::getId)
            .filter(id -> id != null && !id.isBlank())
            .toList());
    }

    private static List<Node> allNodes(Node root) {
        Set<Node> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Node> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            Node node = pending.pop();
            if (node == null || !seen.add(node)) {
                continue;
            }
            // Tab and scroll-pane content is followed directly, so it is found whether or not the
            // skins exist yet; the children of a control are its skin's internals and are skipped.
            if (node instanceof TabPane tabPane) {
                tabPane.getTabs().forEach(tab -> pending.push(tab.getContent()));
            } else if (node instanceof ScrollPane scrollPane) {
                pending.push(scrollPane.getContent());
            } else if (node instanceof Parent parent && !(node instanceof Control)) {
                parent.getChildrenUnmodifiable().forEach(pending::push);
            }
        }
        return new ArrayList<>(seen);
    }

    Tab tab(String id) {
        return find("mainTabs", TabPane.class).getTabs().stream()
            .filter(tab -> id.equals(tab.getId()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No tab with id '" + id + "'"));
    }

    @SuppressWarnings("unchecked")
    <T> TableView<T> table(String id) {
        return find(id, TableView.class);
    }

    @SuppressWarnings("unchecked")
    <T> ComboBox<T> comboBox(String id) {
        return find(id, ComboBox.class);
    }

    // ---- Acting like the user (call from the test thread) ----

    void selectTab(String id) {
        Fx.run(() -> find("mainTabs", TabPane.class).getSelectionModel().select(tab(id)));
    }

    /** Clicks an enabled button; clicking a disabled one is a test error, since the user could not. */
    void click(String id) {
        Fx.run(() -> {
            ButtonBase button = find(id, ButtonBase.class);
            assertFalse(button.isDisabled(), "#" + id + " is disabled");
            button.fire();
        });
    }

    void type(String id, String text) {
        Fx.run(() -> find(id, TextInputControl.class).setText(text));
        lastInputNanos = System.nanoTime();
    }

    /** Presses Enter in a text field. */
    void pressEnter(String id) {
        Fx.run(() -> {
            TextField field = find(id, TextField.class);
            assertFalse(field.isDisabled(), "#" + id + " is disabled");
            field.fireEvent(new ActionEvent(field, field));
        });
    }

    /**
     * Presses and releases a key the way the keyboard does: the event goes to {@code targetId}, or to
     * the window's focus owner when it is null, through the scene's event filters.
     */
    void pressKey(String targetId, KeyCode code) {
        Fx.run(() -> {
            Node target = targetId != null ? find(targetId, Node.class)
                : stage.getScene().getFocusOwner() != null ? stage.getScene().getFocusOwner()
                : stage.getScene().getRoot();
            String text = code.isDigitKey() || code == KeyCode.SPACE ? code.getChar() : "";
            target.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, KeyEvent.CHAR_UNDEFINED, text, code,
                false, false, false, false));
            target.fireEvent(new KeyEvent(KeyEvent.KEY_RELEASED, KeyEvent.CHAR_UNDEFINED, text, code,
                false, false, false, false));
        });
    }

    <T> void select(String comboBoxId, Predicate<T> matcher) {
        Fx.run(() -> {
            ComboBox<T> comboBox = comboBox(comboBoxId);
            T item = comboBox.getItems().stream()
                .filter(matcher)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No matching item in #" + comboBoxId + ": " + comboBox.getItems()));
            comboBox.getSelectionModel().select(item);
        });
    }

    void selectDeck(String comboBoxId, String deckName) {
        this.<Deck>select(comboBoxId, deck -> deck.getName().equals(deckName));
    }

    // ---- Reading state (call from the test thread) ----

    String text(String id) {
        return Fx.call(() -> {
            Node node = find(id, Node.class);
            if (node instanceof Labeled labeled) {
                return labeled.getText();
            }
            if (node instanceof TextInputControl input) {
                return input.getText();
            }
            throw new AssertionError("#" + id + " has no text: " + node);
        });
    }

    /** The id of the node that has the keyboard focus in the window, or null. */
    String focusOwnerId() {
        return Fx.call(() -> {
            Node owner = stage.getScene().getFocusOwner();
            return owner == null ? null : owner.getId();
        });
    }

    boolean isDisabled(String id) {
        return Fx.call(() -> find(id, Node.class).isDisabled());
    }

    boolean isVisible(String id) {
        return Fx.call(() -> find(id, Node.class).isVisible());
    }

    int rowCount(String tableId) {
        return Fx.call(() -> table(tableId).getItems().size());
    }

    record ChartPoint(String x, double y) {
    }

    /** The points of a chart's only series. */
    @SuppressWarnings("unchecked")
    List<ChartPoint> chartPoints(String chartId) {
        return Fx.call(() -> {
            XYChart<String, Number> chart = find(chartId, XYChart.class);
            if (chart.getData().size() != 1) {
                throw new AssertionError("#" + chartId + " has " + chart.getData().size() + " series");
            }
            return chart.getData().get(0).getData().stream()
                .map(point -> new ChartPoint(point.getXValue(), point.getYValue().doubleValue()))
                .toList();
        });
    }

    /** The card on the Review tab, read from the database by the English word the question shows. */
    WordCard questionWord() throws SQLException {
        String question = text("reviewWordLabel");
        return services.wordRepository().findByEnglish(currentDeck().getId(), question)
            .orElseThrow(() -> new AssertionError("The question is not a word of the deck: " + question));
    }

    /**
     * What a learner types for an English-to-Chinese card: its first meaning. (Typing the whole
     * multi-meaning gloss scores below 100%; see review finding A3.)
     */
    static String correctAnswer(WordCard word) {
        return word.getChinese().split("[;；,，/、]")[0].trim();
    }

    /** Answers the card on the Review tab, correctly or not, and rates it; returns the card. */
    WordCard review(boolean correctAnswer, String ratingButtonId) throws SQLException {
        selectTab("reviewTab");
        WordCard word = questionWord();
        type("answerField", correctAnswer ? correctAnswer(word) : "完全错误");
        click("submitAnswerButton");
        waitForBackgroundTasks();
        click(ratingButtonId);
        return word;
    }

    Deck currentDeck() {
        return Fx.call(() -> this.<Deck>comboBox("deckSelector").getValue());
    }

    String headerSubtitle() {
        return text("headerSubtitleLabel");
    }

    /** Waits until the label or text field shows {@code expected}, e.g. after a background task. */
    void waitForText(String id, String expected) {
        waitForText(id, "shows '" + expected + "'", expected::equals);
    }

    void waitForTextStartingWith(String id, String prefix) {
        waitForText(id, "starts with '" + prefix + "'", text -> text.startsWith(prefix));
    }

    private void waitForText(String id, String description, Predicate<String> matches) {
        try {
            Fx.waitUntil("#" + id + " " + description, () -> {
                String current = text(id);
                return current != null && matches.test(current);
            });
        } catch (AssertionError e) {
            throw new AssertionError(e.getMessage() + "; it shows '" + text(id) + "'", e);
        }
    }

    /**
     * Waits until no background task of the window is running and its result has been applied on
     * the FX thread.
     */
    void waitForBackgroundTasks() {
        Fx.waitUntil("background tasks finish", () -> Thread.getAllStackTraces().keySet().stream()
            .noneMatch(thread -> thread.isAlive() && BACKGROUND_THREAD_NAME.equals(thread.getName())));
        // A finished task has already queued its result handler; run it.
        Fx.flush();
    }

    /** Saves the window as target/ui-snapshots/{TestClass}-{test}-{name}.png for manual inspection. */
    Path snapshot(String name) {
        BufferedImage image = Fx.call(() -> {
            WritableImage snapshot = stage.getScene().snapshot(null);
            int width = (int) snapshot.getWidth();
            int height = (int) snapshot.getHeight();
            int[] argb = new int[width * height];
            snapshot.getPixelReader().getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), argb, 0, width);
            BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            buffered.setRGB(0, 0, width, height, argb, 0, width);
            return buffered;
        });
        try {
            Files.createDirectories(SNAPSHOT_DIR);
            Path file = SNAPSHOT_DIR.resolve(getClass().getSimpleName() + "-" + testName + "-" + name + ".png");
            ImageIO.write(image, "png", file.toFile());
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot save the snapshot " + name, e);
        }
    }
}
