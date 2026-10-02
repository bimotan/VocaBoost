package com.vocabtrainer.ui;

import com.vocabtrainer.service.cloze.SentenceSpan;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.ArrayList;
import java.util.List;

/**
 * A card with a word's phonetic, part of speech, example sentence (the word in bold), note and tags,
 * one row each; rows without a value are hidden. Used under the checked answer on the Review tab
 * and under the Word List.
 *
 * <p>The nodes have ids that start with the prefix given: {@code <prefix>Card}, {@code <prefix>Title},
 * {@code <prefix>Phonetic}, {@code <prefix>Pos}, {@code <prefix>Example} (a {@link TextFlow} whose
 * bold texts have the style class {@value #TARGET_STYLE_CLASS}), {@code <prefix>Note},
 * {@code <prefix>Tags} and {@code <prefix>Empty}.
 */
public final class WordDetailsCard {
    /** The style class of the word in the example sentence. */
    public static final String TARGET_STYLE_CLASS = "example-target";
    /** A long note is cut off after about this many lines; its tooltip has all of it. */
    private static final double NOTE_MAX_HEIGHT = 92;
    /** The width of the column of row names. */
    private static final double NAME_WIDTH = 100;

    private final VBox root = new VBox(6);
    private final Label title = new Label();
    private final Label phonetic = value();
    private final Label partOfSpeech = value();
    private final TextFlow example = new TextFlow();
    private final Label note = value();
    private final Label tags = value();
    private final Label empty = new Label();
    private final VBox rowBox = new VBox(2);
    private final List<HBox> rows = new ArrayList<>();

    /** @param idPrefix starts the id of every node of the card, e.g. "reviewDetails" */
    public WordDetailsCard(String idPrefix) {
        root.setId(idPrefix + "Card");
        title.setId(idPrefix + "Title");
        phonetic.setId(idPrefix + "Phonetic");
        partOfSpeech.setId(idPrefix + "Pos");
        example.setId(idPrefix + "Example");
        note.setId(idPrefix + "Note");
        tags.setId(idPrefix + "Tags");
        empty.setId(idPrefix + "Empty");

        title.setStyle("-fx-font-size: 15px; -fx-font-weight: 700;");
        title.setWrapText(true);
        empty.setWrapText(true);
        empty.setStyle("-fx-text-fill: #6b7280;");
        note.setMaxHeight(NOTE_MAX_HEIGHT);

        addRow("Phonetic", phonetic);
        addRow("Part of speech", partOfSpeech);
        addRow("Example", example);
        addRow("Note", note);
        addRow("Tags", tags);

        root.getChildren().addAll(title, rowBox, empty);
        root.setPadding(new Insets(10, 14, 10, 14));
        root.setStyle("-fx-background-color: #f8fafc; -fx-border-color: #cbd5e1;"
            + " -fx-border-radius: 6; -fx-background-radius: 6;");
        show(title, false);
        show(empty, false);
    }

    public Node root() {
        return root;
    }

    /**
     * Shows {@code details}, under {@code heading} unless it is empty. When there is nothing to show,
     * only {@code emptyText} is shown.
     */
    public void show(String heading, WordDetails details, String emptyText) {
        title.setText(heading == null ? "" : heading);
        show(title, !title.getText().isEmpty());
        phonetic.setText(details.phonetic());
        partOfSpeech.setText(details.partOfSpeech());
        example.getChildren().setAll(exampleTexts(details.example()));
        note.setText(details.note());
        note.setTooltip(details.note().isEmpty() ? null : new Tooltip(details.note()));
        tags.setText(details.tags());
        showRow(0, !details.phonetic().isEmpty());
        showRow(1, !details.partOfSpeech().isEmpty());
        showRow(2, !details.example().isEmpty());
        showRow(3, !details.note().isEmpty());
        showRow(4, !details.tags().isEmpty());
        empty.setText(emptyText == null ? "" : emptyText);
        show(empty, details.isEmpty() && !empty.getText().isEmpty());
    }

    /** Shows only {@code message}, e.g. that no word is selected. */
    public void showMessage(String message) {
        show("", new WordDetails("", "", List.of(), "", ""), message);
    }

    /**
     * One row: the name, then the value in the rest of the width. A row of its own gives a value
     * that wraps (the example, a long note) the height it needs at the width it gets, so its lines
     * never overlap the rows around it, also when the card has less room than it would like.
     */
    private void addRow(String name, Region value) {
        Label nameLabel = new Label(name);
        nameLabel.setStyle("-fx-text-fill: #4b5563; -fx-font-weight: 600;");
        nameLabel.setPadding(new Insets(2, 0, 2, 0));
        nameLabel.setMinWidth(NAME_WIDTH);
        nameLabel.setPrefWidth(NAME_WIDTH);
        if (value instanceof TextFlow flow) {
            flow.setPadding(new Insets(2, 0, 2, 0));
        }
        value.setMinWidth(0);
        value.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(value, Priority.ALWAYS);
        HBox row = new HBox(12, nameLabel, value);
        row.setAlignment(Pos.TOP_LEFT);
        rows.add(row);
        rowBox.getChildren().add(row);
    }

    private void showRow(int row, boolean visible) {
        HBox shown = rows.get(row);
        show(shown, visible);
        shown.getChildren().forEach(node -> show(node, visible));
    }

    private static List<Text> exampleTexts(List<SentenceSpan> spans) {
        List<Text> texts = new ArrayList<>();
        for (SentenceSpan span : spans) {
            Text text = new Text(span.text());
            if (span.target()) {
                text.getStyleClass().add(TARGET_STYLE_CLASS);
                text.setStyle("-fx-font-weight: bold; -fx-fill: #1d4ed8;");
            }
            texts.add(text);
        }
        return texts;
    }

    private static Label value() {
        Label label = new Label();
        label.setWrapText(true);
        // Level with the row's name when a long note is cut short to fewer lines than it has.
        label.setAlignment(Pos.TOP_LEFT);
        label.setPadding(new Insets(2, 0, 2, 0));
        return label;
    }

    private static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }
}
