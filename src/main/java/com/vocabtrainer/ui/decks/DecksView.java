package com.vocabtrainer.ui.decks;

import com.vocabtrainer.service.DeckOverview;
import com.vocabtrainer.service.DeckService;
import com.vocabtrainer.service.StatsService;
import com.vocabtrainer.ui.DataChange;
import com.vocabtrainer.ui.LazyRefresh;
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

import java.util.List;

/** The Decks tab: active and archived decks with their word, due and latest-review counts. */
public final class DecksView {
    private final ViewContext context;
    private final DeckService deckService;
    private final StatsService statsService;
    private final ObservableList<DeckOverview> deckRows = FXCollections.observableArrayList();
    private final ObservableList<DeckOverview> archivedDeckRows = FXCollections.observableArrayList();
    private final TableView<DeckOverview> deckTable = new TableView<>(deckRows);
    private final TableView<DeckOverview> archivedDeckTable = new TableView<>(archivedDeckRows);
    private final Tab tab;
    private final LazyRefresh lazy;

    public DecksView(ViewContext context, DeckService deckService, StatsService statsService) {
        this.context = context;
        this.deckService = deckService;
        this.statsService = statsService;
        this.tab = Widgets.tab("decksTab", "Decks", createContent());
        this.lazy = new LazyRefresh(tab, this::refresh, context.errors(), "Refresh decks failed", false);
        context.changes().subscribe(changes -> {
            if (changes.contains(DataChange.WORDS) || changes.contains(DataChange.REVIEWS)
                || changes.contains(DataChange.DECKS) || changes.contains(DataChange.REVIEW_SETTINGS)) {
                lazy.markStale();
            }
        });
        context.decks().onSwitch(deck -> lazy.markStale());
    }

    public Tab tab() {
        return tab;
    }

    /** Loads the content now, whether the tab is shown or not. */
    public void refreshNow() {
        lazy.refreshNow();
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
            DeckOverview selected = deckTable.getSelectionModel().getSelectedItem();
            if (selected != null) {
                context.errors().guard("Switch deck failed", () -> context.decks().switchTo(selected.deck()));
            }
        });
        Button restoreButton = new Button("Restore selected archived deck");
        restoreButton.setId("restoreDeckButton");
        restoreButton.setOnAction(event -> restoreSelectedArchivedDeck());
        Button refreshButton = new Button("Refresh");
        refreshButton.setId("refreshDecksButton");
        refreshButton.setOnAction(event -> lazy.refreshNow());
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

    private static void configureDeckTable(TableView<DeckOverview> table) {
        TableColumn<DeckOverview, String> nameCol = new TableColumn<>("Deck");
        nameCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().deck().getName()));
        TableColumn<DeckOverview, String> wordsCol = new TableColumn<>("Words");
        wordsCol.setCellValueFactory(data -> new SimpleStringProperty(String.valueOf(data.getValue().words())));
        TableColumn<DeckOverview, String> dueCol = new TableColumn<>("Due");
        dueCol.setCellValueFactory(data -> new SimpleStringProperty(String.valueOf(data.getValue().due())));
        TableColumn<DeckOverview, String> latestCol = new TableColumn<>("Latest review");
        latestCol.setCellValueFactory(data ->
            new SimpleStringProperty(DateTimeUtil.toDisplay(data.getValue().latestReviewAt())));
        table.getColumns().addAll(List.of(nameCol, wordsCol, dueCol, latestCol));
    }

    private void restoreSelectedArchivedDeck() {
        DeckOverview selected = archivedDeckTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            context.errors().showInfo("Please select an archived deck to restore.");
            return;
        }
        context.errors().guard("Restore deck failed", () -> {
            context.decks().switchTo(deckService.restoreDeck(selected.deck().getId()));
            context.changes().publish(DataChange.DECKS);
        });
    }

    private void refresh() {
        try {
            deckRows.setAll(statsService.deckOverviews(deckService.activeDecks()));
            archivedDeckRows.setAll(statsService.deckOverviews(deckService.archivedDecks()));
        } catch (RuntimeException e) {
            context.errors().reportFailure("Refresh decks failed", e);
        }
    }
}
