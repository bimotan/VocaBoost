package com.vocabtrainer.ui;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.service.DeckService;
import com.vocabtrainer.service.SettingsService;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.Optional;

/**
 * The window header: app title, deck and service status, the offline mode switch (the same one as on
 * the Settings tab), and the deck selector with create, rename and archive.
 */
public final class DeckHeader {
    private final ViewContext context;
    private final DeckService deckService;
    private final SettingsService settingsService;
    private final ConfiguredServices configured;
    private final Label subtitleLabel = new Label();
    private final ComboBox<Deck> deckSelector = Widgets.deckComboBox("deckSelector", 220);
    private final VBox root;
    private boolean showingDecks;

    /** {@code offlineMode} is the switch the header shows next to the title. */
    public DeckHeader(ViewContext context, DeckService deckService, SettingsService settingsService,
                      ConfiguredServices configured, OfflineMode offlineMode) {
        this.context = context;
        this.deckService = deckService;
        this.settingsService = settingsService;
        this.configured = configured;

        Label title = new Label("VocaBoost");
        title.setStyle("-fx-font-size: 24px; -fx-font-weight: 700;");
        subtitleLabel.setStyle("-fx-text-fill: #4b5563;");
        subtitleLabel.setId("headerSubtitleLabel");

        DeckContext decks = context.decks();
        deckSelector.valueProperty().addListener((observable, oldDeck, newDeck) -> {
            // Only the user's choice switches decks, not the selector catching up with the deck list.
            if (!showingDecks && newDeck != null && newDeck.getId() != decks.currentId()) {
                context.errors().guard("Switch deck failed", () -> decks.switchTo(newDeck));
            }
        });
        decks.activeDecks().addListener((ListChangeListener<Deck>) change -> showDecks());
        decks.currentProperty().addListener((observable, oldDeck, newDeck) -> {
            showDecks();
            updateSubtitle();
        });
        context.changes().subscribe(changes -> {
            if (changes.contains(DataChange.SETTINGS)) {
                updateSubtitle();
            }
        });

        Button newDeckButton = new Button("New deck");
        newDeckButton.setId("newDeckButton");
        newDeckButton.setOnAction(event -> createDeck());
        Button renameDeckButton = new Button("Rename");
        renameDeckButton.setId("renameDeckButton");
        renameDeckButton.setOnAction(event -> renameCurrentDeck());
        Button archiveDeckButton = new Button("Archive");
        archiveDeckButton.setId("archiveDeckButton");
        archiveDeckButton.setOnAction(event -> archiveCurrentDeck());

        HBox deckControls = new HBox(8, new Label("Deck"), deckSelector, newDeckButton, renameDeckButton, archiveDeckButton);
        deckControls.setAlignment(Pos.CENTER_LEFT);
        CheckBox offlineToggle = offlineMode.checkBox("offlineModeToggle", "Offline mode / 离线模式");
        HBox titleLine = new HBox(18, title, offlineToggle);
        titleLine.setAlignment(Pos.CENTER_LEFT);

        HBox headerLine = new HBox(24, new VBox(4, titleLine, subtitleLabel), deckControls);
        headerLine.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(headerLine.getChildren().get(0), Priority.ALWAYS);
        showDecks();
        updateSubtitle();
        root = new VBox(4, headerLine);
        root.setPadding(new Insets(18, 24, 12, 24));
        root.setStyle("-fx-background-color: #f8fafc; -fx-border-color: #e5e7eb; -fx-border-width: 0 0 1 0;");
    }

    public Node root() {
        return root;
    }

    private void showDecks() {
        showingDecks = true;
        try {
            Widgets.showDecks(deckSelector, context.decks().activeDecks(), context.decks().currentId());
        } finally {
            showingDecks = false;
        }
    }

    private void updateSubtitle() {
        boolean offline = settingsService.isOfflineMode();
        String dictionaryStatus = settingsService.getEcdictPath().isPresent() ? "ECDICT configured"
            : offline ? "starter words" : "starter/online fallback";
        subtitleLabel.setText("Deck: " + context.decks().current().getName() + " | Dictionary: " + dictionaryStatus
            + " | AI: " + (configured.ai().isAvailable() ? "configured" : "mock")
            + (offline ? " | Offline mode" : ""));
    }

    private void createDeck() {
        Optional<String> name = context.dialogs().askText("New deck", "Create a new deck", "Deck name", "");
        name.ifPresent(value -> context.errors().guard("Create deck failed", () -> {
            Deck created = deckService.createDeck(value);
            context.decks().switchTo(created);
            context.changes().publish(DataChange.DECKS);
        }));
    }

    private void renameCurrentDeck() {
        Deck deck = context.decks().current();
        Optional<String> name = context.dialogs().askText("Rename deck", "Rename current deck", "Deck name", deck.getName());
        name.ifPresent(value -> context.errors().guard("Rename deck failed", () -> {
            deckService.renameDeck(deck.getId(), value);
            context.decks().reloadDecks();
            context.changes().publish(DataChange.DECKS);
        }));
    }

    private void archiveCurrentDeck() {
        Deck deck = context.decks().current();
        boolean confirmed = context.dialogs().confirm("Archive deck", "Archive " + deck.getName() + "?",
            "Words remain in SQLite, but the deck will be hidden from active study views.");
        if (confirmed) {
            context.errors().guard("Archive deck failed", () -> {
                Deck next = deckService.archiveDeck(deck.getId());
                context.decks().switchTo(next);
                context.changes().publish(DataChange.DECKS);
            });
        }
    }
}
