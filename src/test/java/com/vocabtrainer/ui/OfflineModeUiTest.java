package com.vocabtrainer.ui;

import com.sun.net.httpserver.HttpServer;
import com.vocabtrainer.app.AppServices;
import com.vocabtrainer.domain.DictionaryEntry;
import com.vocabtrainer.domain.DictionaryLookupResult;
import com.vocabtrainer.service.AiServiceFactory;
import com.vocabtrainer.service.DictionaryService;
import com.vocabtrainer.service.DictionaryServiceFactory;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextInputControl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The header's offline mode switch: while it is on, no AI request and no online dictionary lookup
 * is made. The AI provider is the app's own composition talking to a local HTTP server, which
 * counts the requests that reach it.
 */
@Tag("ui")
class OfflineModeUiTest extends MainWindowUiTest {
    private static final String COMPLETION = "{\"choices\":[{\"message\":{\"content\":\"From the provider.\"}}]}";

    private final AtomicInteger aiRequests = new AtomicInteger();
    private final CountingOnlineDictionary online = new CountingOnlineDictionary();
    private HttpServer server;

    @Override
    AppServices.Builder configure(AppServices.Builder builder) {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", exchange -> {
            aiRequests.incrementAndGet();
            byte[] body = COMPLETION.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
            exchange.close();
        });
        server.start();
        Map<String, String> aiConfig = Map.of(
            "VOCABOOST_AI_BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
            "VOCABOOST_AI_API_KEY", "test-key",
            "VOCABOOST_AI_MODEL", "model-a");
        return builder
            .aiService((cache, settings) -> AiServiceFactory.create(cache, settings, aiConfig))
            // The app's chain with a counting stand-in for the public online dictionaries.
            .dictionaryService((cache, local) -> DictionaryServiceFactory.compose(local, null, online, cache,
                clock, () -> services.settingsService().isOfflineMode()));
    }

    @AfterEach
    void stopServer() {
        waitForBackgroundTasks();
        server.stop(0);
    }

    @Test
    void theHeaderSwitchesOfflineModeAndThenNoAiRequestIsSent() {
        assertEquals("Deck: " + STARTER_DECK + " | Dictionary: starter/online fallback | AI: configured", headerSubtitle());
        assertFalse(Fx.call(() -> find("offlineModeToggle", CheckBox.class).isSelected()));

        click("offlineModeToggle");

        assertTrue(services.settingsService().isOfflineMode());
        assertEquals("Deck: " + STARTER_DECK + " | Dictionary: starter words | AI: mock | Offline mode", headerSubtitle());
        selectTab("reviewTab");
        type("answerField", "完全错误");
        click("submitAnswerButton");
        waitForBackgroundTasks();
        assertTrue(text("reviewResultArea").endsWith("Offline mode is on: no AI request was sent."), text("reviewResultArea"));
        assertFalse(isVisible("regenerateExplanationButton"));

        selectTab("settingsTab");
        assertTrue(text("aiStatusLabel").startsWith("Offline mode is on"), text("aiStatusLabel"));
        click("testAiButton");
        waitForBackgroundTasks();
        assertTrue(text("aiStatusLabel").startsWith("AI test skipped: offline mode is on"), text("aiStatusLabel"));
        assertEquals(0, aiRequests.get(), "no AI request while offline");

        click("offlineModeToggle");

        assertFalse(services.settingsService().isOfflineMode());
        assertEquals("Deck: " + STARTER_DECK + " | Dictionary: starter/online fallback | AI: configured", headerSubtitle());
        // Back online, the card still on screen can be explained by the provider at once.
        selectTab("reviewTab");
        assertTrue(isVisible("regenerateExplanationButton"));
        click("regenerateExplanationButton");
        waitForBackgroundTasks();
        assertTrue(text("reviewResultArea").endsWith("From the provider."), text("reviewResultArea"));
        assertEquals(1, aiRequests.get());
    }

    @Test
    void offlineLookupsSayWhyAndAWordIsAddedUncheckedWithoutRetry() throws Exception {
        click("offlineModeToggle");
        selectTab("addImportTab");

        type("lookupField", "zyzzyva");
        click("lookupButton");
        waitForBackgroundTasks();

        assertTrue(text("lookupStatusLabel").startsWith("Offline mode is on"), text("lookupStatusLabel"));
        assertFalse(isVisible("lookupRetryButton"), "asking again cannot help while offline");

        type("addEnglishField", "zyzzyva");
        type("addChineseField", "一种象鼻虫");
        dialogs.chooseButton("Add anyway");
        click("addWordButton");
        waitForText("addWordStatusLabel", "Added to " + STARTER_DECK + ": zyzzyva | Not checked: offline mode is on");

        assertEquals("Add anyway | Cancel", dialogs.last(ScriptedDialogs.Kind.CHOOSE).value());
        assertEquals("UNCHECKED", services.wordRepository().findByEnglish(currentDeck().getId(), "zyzzyva")
            .orElseThrow().getTags());
        assertEquals(0, online.calls.get(), "the online dictionaries were not asked");

        click("offlineModeToggle");
        type("lookupField", "zyzzyva");
        click("lookupButton");
        waitForBackgroundTasks();
        assertEquals(1, online.calls.get(), "back online, they are asked again");
    }

    @Test
    void theDashboardDoesNotShowTheDataFolderPathOrTheAccountName() {
        selectTab("dashboardTab");

        assertTrue(isVisible("dashboardDataFolderButton"));
        String folder = tempDir.toAbsolutePath().toString();
        String home = System.getProperty("user.home");
        for (String id : allIds()) {
            String shown = Fx.call(() -> {
                Node node = find(id, Node.class);
                return node instanceof Labeled labeled ? labeled.getText()
                    : node instanceof TextInputControl input ? input.getText() : "";
            });
            if (shown != null) {
                assertFalse(shown.contains(folder) || shown.contains(home), "#" + id + " shows a path: " + shown);
            }
        }
    }

    /** Finds every word, and counts how often it was asked. */
    static final class CountingOnlineDictionary implements DictionaryService {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public DictionaryLookupResult lookup(String english) {
            calls.incrementAndGet();
            return DictionaryLookupResult.success("Loaded from the online stand-in.",
                List.of(new DictionaryEntry(english, "", "noun", "", "", "Online stand-in")));
        }

        @Override
        public boolean isConfigured() {
            return true;
        }
    }
}
