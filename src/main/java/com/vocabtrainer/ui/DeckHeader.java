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
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Optional;

import static com.vocabtrainer.util.Messages.tr;

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

        Label title = Widgets.styled(new Label(tr("app.title")), "app-title");
        subtitleLabel.getStyleClass().add("secondary-text");
        subtitleLabel.setId("headerSubtitleLabel");

        DeckContext decks = context.decks();
        deckSelector.valueProperty().addListener((observable, oldDeck, newDeck) -> {
            // Only the user's choice switches decks, not the selector catching up with the deck list.
            if (!showingDecks && newDeck != null && newDeck.getId() != decks.currentId()) {
                context.errors().guard(tr("header.switchDeck.failed"), () -> decks.switchTo(newDeck));
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

        Button newDeckButton = new Button(tr("header.newDeck"));
        newDeckButton.setId("newDeckButton");
        newDeckButton.setOnAction(event -> createDeck());
        Button renameDeckButton = new Button(tr("header.rename"));
        renameDeckButton.setId("renameDeckButton");
        renameDeckButton.setOnAction(event -> renameCurrentDeck());
        Button archiveDeckButton = new Button(tr("header.archive"));
        archiveDeckButton.setId("archiveDeckButton");
        archiveDeckButton.setOnAction(event -> archiveCurrentDeck());

        Label deckLabel = Widgets.formLabel(tr("header.deck"), deckSelector);
        HBox deckControls = new HBox(8, deckLabel, deckSelector, newDeckButton, renameDeckButton, archiveDeckButton);
        deckControls.setAlignment(Pos.CENTER_LEFT);
        CheckBox offlineToggle = offlineMode.checkBox("offlineModeToggle", tr("offline.toggle"));
        // In a narrow window the status line is cut short first, then the deck selector, never the
        // title or the buttons.
        for (Region region : List.of(title, deckLabel, newDeckButton, renameDeckButton, archiveDeckButton)) {
            region.setMinWidth(Region.USE_PREF_SIZE);
        }
        deckSelector.setMinWidth(120);
        HBox titleLine = new HBox(18, title, offlineToggle);
        titleLine.setAlignment(Pos.CENTER_LEFT);

        HBox headerLine = new HBox(24, new VBox(4, titleLine, subtitleLabel), deckControls);
        headerLine.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(headerLine.getChildren().get(0), Priority.ALWAYS);
        showDecks();
        updateSubtitle();
        root = new VBox(4, headerLine);
        root.setPadding(new Insets(18, 24, 12, 24));
        root.getStyleClass().add("app-header");
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
        String dictionaryStatus = settingsService.getEcdictPath().isPresent() ? tr("header.dictionary.ecdict")
            : offline ? tr("header.dictionary.starter") : tr("header.dictionary.starterOnline");
        String ai = configured.ai().isAvailable() ? tr("header.ai.configured") : tr("header.ai.mock");
        String subtitle = tr("header.subtitle", context.decks().current().getName(), dictionaryStatus, ai);
        subtitleLabel.setText(offline ? tr("header.subtitle.offline", subtitle) : subtitle);
    }

    private void createDeck() {
        Optional<String> name = context.dialogs().askText(tr("header.newDeck.title"), tr("header.newDeck.header"),
            tr("header.deckName"), "");
        name.ifPresent(value -> context.errors().guard(tr("header.newDeck.failed"), () -> {
            Deck created = deckService.createDeck(value);
            context.decks().switchTo(created);
            context.changes().publish(DataChange.DECKS);
        }));
    }

    private void renameCurrentDeck() {
        Deck deck = context.decks().current();
        Optional<String> name = context.dialogs().askText(tr("header.rename.title"), tr("header.rename.header"),
            tr("header.deckName"), deck.getName());
        name.ifPresent(value -> context.errors().guard(tr("header.rename.failed"), () -> {
            deckService.renameDeck(deck.getId(), value);
            context.decks().reloadDecks();
            context.changes().publish(DataChange.DECKS);
        }));
    }

    private void archiveCurrentDeck() {
        Deck deck = context.decks().current();
        boolean confirmed = context.dialogs().confirm(tr("header.archive.title"),
            tr("header.archive.header", deck.getName()), tr("header.archive.content"));
        if (confirmed) {
            context.errors().guard(tr("header.archive.failed"), () -> {
                Deck next = deckService.archiveDeck(deck.getId());
                context.decks().switchTo(next);
                context.changes().publish(DataChange.DECKS);
            });
        }
    }
}
