package com.vocabtrainer.ui;

import com.vocabtrainer.domain.Deck;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.layout.Region;

import java.util.List;

/** Small JavaFX building blocks shared by the views. */
public final class Widgets {
    private Widgets() {
    }

    public static Label sectionTitle(String text) {
        return styled(new Label(text), "section-title");
    }

    /**
     * A form field's label, never cut short. Screen readers read it as {@code field}'s name, and the
     * letter after "_" in {@code text} is a mnemonic: Alt with that letter moves the focus to {@code field}.
     */
    public static Label formLabel(String text, Node field) {
        Label label = new Label(text);
        label.setMnemonicParsing(true);
        label.setLabelFor(field);
        label.setMinWidth(Region.USE_PREF_SIZE);
        return label;
    }

    /** A wrapping note under a setting or a form field, in smaller, muted text. */
    public static Label hint(String text) {
        Label label = styled(new Label(text), "hint-text");
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }

    /** Adds the app.css style classes to {@code node}; returns it. */
    public static <T extends Node> T styled(T node, String... styleClasses) {
        node.getStyleClass().addAll(styleClasses);
        return node;
    }

    /**
     * Wraps a tab's content so that it scrolls when the window is shorter than the content, or
     * narrower than the content can shrink to. The content is as wide as the tab; with
     * {@code fillHeight} it also fills the tab's height, shrinking towards its minimum height before it
     * scrolls, so its parts that should give up height first need a small minimum and the others one
     * of {@code USE_PREF_SIZE}.
     */
    public static ScrollPane tabScroll(Node content, boolean fillHeight) {
        ScrollPane scrollPane = styled(new ScrollPane(content), "tab-scroll");
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(fillHeight);
        // The viewport caches its content as a bitmap, which would draw the text without subpixel
        // smoothing; the text looks as it does outside a scroll pane without it.
        scrollPane.skinProperty().addListener((observable, oldSkin, skin) -> {
            Node viewport = scrollPane.lookup(".viewport");
            if (viewport != null) {
                viewport.setCache(false);
            }
        });
        return scrollPane;
    }

    /** A tab that cannot be closed. */
    public static Tab tab(String id, String title, Node content) {
        Tab tab = new Tab(title, content);
        tab.setId(id);
        tab.setClosable(false);
        return tab;
    }

    /** A combo box that lists decks by name. */
    public static ComboBox<Deck> deckComboBox(String id, double prefWidth) {
        ComboBox<Deck> comboBox = new ComboBox<>();
        comboBox.setId(id);
        comboBox.setPrefWidth(prefWidth);
        comboBox.setCellFactory(list -> deckCell());
        comboBox.setButtonCell(deckCell());
        return comboBox;
    }

    /** Shows {@code decks} in {@code comboBox} and selects the one with {@code selectedId}, or the first. */
    public static void showDecks(ComboBox<Deck> comboBox, List<Deck> decks, long selectedId) {
        comboBox.getItems().setAll(decks);
        decks.stream()
            .filter(deck -> deck.getId() == selectedId)
            .findFirst()
            .or(() -> decks.stream().findFirst())
            .ifPresent(deck -> comboBox.getSelectionModel().select(deck));
    }

    private static ListCell<Deck> deckCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(Deck item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getName());
            }
        };
    }
}
