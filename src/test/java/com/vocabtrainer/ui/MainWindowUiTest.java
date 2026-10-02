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
import com.vocabtrainer.service.ReviewSettings;
import javafx.event.ActionEvent;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.chart.XYChart;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.Labeled;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
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
    /** The default new-words-per-day limit: how many of the new starter words are due on a day. */
    static final int NEW_WORDS_PER_DAY = ReviewSettings.DEFAULT_NEW_CARDS_PER_DAY;
    static final Path SNAPSHOT_DIR = Path.of("target", "ui-snapshots");

    private static final String BACKGROUND_THREAD_NAME = "vocaboost-background-task";
    /** Longer than a debounce of typed input (the Word List's search had one of 250 ms). */
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
        showMainWindow();
    }

    private void showMainWindow() {
        AppServices.Builder builder = AppServices.builder(tempDir.resolve("vocab.db"))
            .dictionaryService((cache, local) -> offlineDictionary(local))
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

    /**
     * Closes the window and the database and opens both again on the same file, as quitting and
     * starting the app again does.
     */
    void restartApp() {
        waitForBackgroundTasks();
        Fx.run(() -> stage.close());
        services.close();
        services = null;
        showMainWindow();
    }

    /**
     * Override to replace part of the wiring, for example with a repository that fails on demand, or
     * to prepare files before the window opens ({@link #testName()} tells which test is starting).
     */
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
            // Release the pooled connections so the @TempDir database can be deleted (Windows).
            if (services != null) {
                services.close();
            }
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
     * The app's offline dictionaries (imported ECDICT, then the bundled starter words) followed by a
     * few extra test words, in place of the online dictionaries.
     */
    static DictionaryService offlineDictionary(LocalDictionaryService local) {
        return new CompositeDictionaryService(List.of(local, new TestDictionary()));
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
                ? DictionaryLookupResult.notFound("词条未找到：测试词典没有该词条。")
                : DictionaryLookupResult.success("Loaded from the test dictionary.", List.of(entry));
        }

        @Override
        public boolean isConfigured() {
            return true;
        }
    }

    /** The name of the test method that is running. */
    String testName() {
        return testName;
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

    /**
     * Every node MainWindow built (not the internals of control skins), on every tab, selected or not;
     * call on the FX thread.
     */
    List<Node> windowNodes() {
        return allNodes(stage.getScene().getRoot());
    }

    /** The nodes of the tab {@code tabId}, selected or not; call on the FX thread. */
    List<Node> tabNodes(String tabId) {
        return allNodes(tab(tabId).getContent());
    }

    /** Resizes the window, as the user would by dragging its corner, and waits until its content has the size. */
    void resizeWindow(double width, double height) {
        Fx.run(() -> {
            stage.setWidth(width);
            stage.setHeight(height);
        });
        Fx.waitUntil("the window is " + width + " x " + height, () -> stage.getWidth() == width
            && stage.getHeight() == height);
        Fx.run(() -> {
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
        });
    }

    /** The window's smallest size: {min width, min height}. */
    double[] minimumWindowSize() {
        return Fx.call(() -> new double[] {stage.getMinWidth(), stage.getMinHeight()});
    }

    /** Whether the node with this id and its parents are visible and it lies entirely inside the window's content. */
    boolean isEntirelyInWindow(String id) {
        return Fx.call(() -> {
            Node node = find(id, Node.class);
            Bounds bounds = node.localToScene(node.getBoundsInLocal());
            Scene scene = stage.getScene();
            for (Node shown = node; shown != null; shown = shown.getParent()) {
                if (!shown.isVisible()) {
                    return false;
                }
            }
            return bounds.getMinX() >= 0 && bounds.getMinY() >= 0
                && bounds.getMaxX() <= scene.getWidth() && bounds.getMaxY() <= scene.getHeight();
        });
    }

    /** The id of every node on the tab {@code tabId} that has one, whether or not the tab is selected. */
    List<String> idsOnTab(String tabId) {
        return Fx.call(() -> allNodes(tab(tabId).getContent()).stream()
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
     * Types a key the way the keyboard does: pressed, typed (for a character key) and released, each
     * passing the scene's event filters. The events go to {@code targetId}, or, when it is null, each
     * to whichever node has the focus at that moment, as the window delivers them.
     */
    void pressKey(String targetId, KeyCode code) {
        String character = keyCharacter(code);
        pressKeyDown(targetId, code);
        if (!character.isEmpty()) {
            fireKey(targetId, new KeyEvent(KeyEvent.KEY_TYPED, character, "", KeyCode.UNDEFINED,
                false, false, false, false));
        }
        fireKey(targetId, new KeyEvent(KeyEvent.KEY_RELEASED, KeyEvent.CHAR_UNDEFINED, character, code,
            false, false, false, false));
    }

    /**
     * Only the key-pressed event of a key, as when its typed and released events go to another window,
     * such as a dialog opened by the key press.
     */
    void pressKeyDown(String targetId, KeyCode code) {
        fireKey(targetId, new KeyEvent(KeyEvent.KEY_PRESSED, KeyEvent.CHAR_UNDEFINED, keyCharacter(code), code,
            false, false, false, false));
    }

    /**
     * Presses a key with the platform's shortcut modifier, as Ctrl+Z (Cmd+Z on a Mac) does: pressed and
     * released, each passing the scene's event filters; see {@link #pressKey} for {@code targetId}.
     */
    void pressShortcut(String targetId, KeyCode code) {
        boolean mac = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
        fireKey(targetId, new KeyEvent(KeyEvent.KEY_PRESSED, KeyEvent.CHAR_UNDEFINED, "", code,
            false, !mac, false, mac));
        fireKey(targetId, new KeyEvent(KeyEvent.KEY_RELEASED, KeyEvent.CHAR_UNDEFINED, "", code,
            false, !mac, false, mac));
    }

    /** The character a digit, letter (lower case) or Space key types; empty for other keys. */
    private static String keyCharacter(KeyCode code) {
        if (code.isDigitKey() || code == KeyCode.SPACE) {
            return code.getChar();
        }
        return code.isLetterKey() ? code.getChar().toLowerCase(Locale.ROOT) : "";
    }

    private void fireKey(String targetId, KeyEvent event) {
        Fx.run(() -> {
            Node target = targetId != null ? find(targetId, Node.class)
                : stage.getScene().getFocusOwner() != null ? stage.getScene().getFocusOwner()
                : stage.getScene().getRoot();
            target.fireEvent(event);
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

    /** The text in column {@code column} of the row whose first column shows {@code firstColumn}. */
    String cell(String tableId, String firstColumn, int column) {
        return Fx.call(() -> {
            TableView<Object> table = table(tableId);
            TableColumn<Object, ?> key = table.getColumns().get(0);
            Object row = table.getItems().stream()
                .filter(item -> firstColumn.equals(String.valueOf(key.getCellData(item))))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No row '" + firstColumn + "' in #" + tableId));
            return String.valueOf(table.getColumns().get(column).getCellData(row));
        });
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

    /** What a learner types for an English-to-Chinese card: its first meaning. */
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
