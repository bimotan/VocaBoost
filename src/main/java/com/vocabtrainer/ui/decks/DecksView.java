package com.vocabtrainer.ui.decks;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.repository.WordRepository;
import com.vocabtrainer.service.DeckService;
import com.vocabtrainer.service.StatsService;
import com.vocabtrainer.ui.DataChange;
import com.vocabtrainer.ui.ViewContext;
import com.vocabtrainer.ui.Widgets;
import com.vocabtrainer.util.DateTimeUtil;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Tab;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/** The Decks tab: active and archived decks with their word, due and latest-review counts. */
public final class DecksView {
    private record DeckRow(Deck deck, String name, int words, int due, String latestReview) {
    }

    private final ViewContext context;
    private final DeckService deckService;
    private final StatsService statsService;
    private final WordRepository wordRepository;
    private final ObservableList<DeckRow> deckRows = FXCollections.observableArrayList();
    private final ObservableList<DeckRow> archivedDeckRows = FXCollections.observableArrayList();
    private final TableView<DeckRow> deckTable = new TableView<>(deckRows);
    private final TableView<DeckRow> archivedDeckTable = new TableView<>(archivedDeckRows);
    private final Tab tab;

    public DecksView(ViewContext context, DeckService deckService, StatsService statsService,
                     WordRepository wordRepository) {
        this.context = context;
        this.deckService = deckService;
        this.statsService = statsService;
        this.wordRepository = wordRepository;
        this.tab = Widgets.tab("decksTab", "Decks", createContent());
        context.changes().subscribe(changes -> {
            if (changes.contains(DataChange.WORDS) || changes.contains(DataChange.REVIEWS)
                || changes.contains(DataChange.DECKS)) {
                refresh();
            }
        });
        context.decks().onSwitch(deck -> refresh());
    }

    public Tab tab() {
        return tab;
    }

    private VBox createContent() {
        deckTable.setId("deckTable");
        deckTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        configureDeckTable(deckTable);

        archivedDeckTable.setId("archivedDeckTable");
        archivedDeckTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        configureDeckTable(archivedDeckTable);
        archivedDeckTable.setPrefHeight(180);

        Button switchButton = new Button("Switch selected");
        switchButton.setId("switchDeckButton");
        switchButton.setOnAction(event -> {
            DeckRow selected = deckTable.getSelectionModel().getSelectedItem();
            if (selected != null) {
                context.errors().guard("Switch deck failed", () -> context.decks().switchTo(selected.deck()));
            }
        });
        Button restoreButton = new Button("Restore selected archived deck");
        restoreButton.setId("restoreDeckButton");
        restoreButton.setOnAction(event -> restoreSelectedArchivedDeck());
        Button refreshButton = new Button("Refresh");
        refreshButton.setId("refreshDecksButton");
        refreshButton.setOnAction(event -> refresh());
        VBox content = new VBox(12,
            Widgets.sectionTitle("Active Decks"),
            deckTable,
            new HBox(10, switchButton, refreshButton),
            Widgets.sectionTitle("Archived Decks"),
            archivedDeckTable,
            restoreButton
        );
        content.setPadding(new Insets(24));
        VBox.setVgrow(deckTable, Priority.ALWAYS);
        return content;
    }

    private static void configureDeckTable(TableView<DeckRow> table) {
        TableColumn<DeckRow, String> nameCol = new TableColumn<>("Deck");
        nameCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().name()));
        TableColumn<DeckRow, String> wordsCol = new TableColumn<>("Words");
        wordsCol.setCellValueFactory(data -> new SimpleStringProperty(String.valueOf(data.getValue().words())));
        TableColumn<DeckRow, String> dueCol = new TableColumn<>("Due");
        dueCol.setCellValueFactory(data -> new SimpleStringProperty(String.valueOf(data.getValue().due())));
        TableColumn<DeckRow, String> latestCol = new TableColumn<>("Latest review");
        latestCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().latestReview()));
        table.getColumns().addAll(List.of(nameCol, wordsCol, dueCol, latestCol));
    }

    private void restoreSelectedArchivedDeck() {
        DeckRow selected = archivedDeckTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            context.errors().showInfo("Please select an archived deck to restore.");
            return;
        }
        context.errors().guard("Restore deck failed", () -> {
            context.decks().switchTo(deckService.restoreDeck(selected.deck().getId()));
            context.changes().publish(DataChange.DECKS);
        });
    }

    public void refresh() {
        try {
            LocalDateTime now = LocalDateTime.now();
            deckRows.setAll(deckRowsFor(deckService.activeDecks(), now));
            archivedDeckRows.setAll(deckRowsFor(deckService.archivedDecks(), now));
        } catch (RuntimeException e) {
            context.errors().reportFailure("Refresh decks failed", e);
        }
    }

    private List<DeckRow> deckRowsFor(List<Deck> decks, LocalDateTime now) {
        return decks.stream()
            .map(deck -> {
                try {
                    LocalDateTime latest = statsService.latestReviewAt(deck.getId());
                    return new DeckRow(
                        deck,
                        deck.getName(),
                        wordRepository.countAll(deck.getId()),
                        wordRepository.countDue(deck.getId(), now),
                        latest == null ? "-" : DateTimeUtil.toDisplay(latest)
                    );
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            })
            .toList();
    }
}
