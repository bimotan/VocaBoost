package com.vocabtrainer.ui;

import com.vocabtrainer.domain.Deck;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Tab;

import java.util.List;

/** Small JavaFX building blocks shared by the views. */
public final class Widgets {
    private Widgets() {
    }

    public static Label sectionTitle(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 16px; -fx-font-weight: 600;");
        return label;
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
