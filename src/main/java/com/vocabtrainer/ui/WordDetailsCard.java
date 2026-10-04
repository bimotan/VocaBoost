package com.vocabtrainer.ui;

import com.vocabtrainer.service.WordExtras;
import com.vocabtrainer.service.cloze.SentenceSpan;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
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

import static com.vocabtrainer.util.Messages.tr;

/**
 * A card with a word's phonetic, part of speech, example sentence (the word in bold), note and tags,
 * one row each; rows without a value are hidden. Used under the checked answer on the Review tab
 * and under the Word List.
 *
 * <p>Below them come the synonyms and antonyms an online dictionary gave for the word, when they
 * are known ({@link #showExtras}); a play button next to the phonetic plays its recording, and a
 * link looks them up when nothing is known yet. {@link WordExtrasController} decides what is offered
 * and does the work; the card only shows it.
 *
 * <p>The nodes have ids that start with the prefix given: {@code <prefix>Card}, {@code <prefix>Title},
 * {@code <prefix>Phonetic}, {@code <prefix>Pos}, {@code <prefix>Example} (a {@link TextFlow} whose
 * bold texts have the style class {@value #TARGET_STYLE_CLASS}), {@code <prefix>Note},
 * {@code <prefix>Tags}, {@code <prefix>Synonyms}, {@code <prefix>Antonyms}, {@code <prefix>PlayButton},
 * {@code <prefix>ExtrasLink}, {@code <prefix>ExtrasStatus} and {@code <prefix>Empty}.
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
    private final Label synonyms = value();
    private final Label antonyms = value();
    private final Button playButton = new Button("▶");
    private final Hyperlink extrasLink = new Hyperlink(tr("extras.lookUp"));
    private final Label extrasStatus = new Label();
    private final Label empty = new Label();
    private final VBox rowBox = new VBox(2);
    private final List<HBox> rows = new ArrayList<>();
    /** Whether the shown word has a phonetic, whose row the play button shares. */
    private boolean phoneticShown;

    /** @param idPrefix starts the id of every node of the card, e.g. "reviewDetails" */
    public WordDetailsCard(String idPrefix) {
        root.setId(idPrefix + "Card");
        title.setId(idPrefix + "Title");
        phonetic.setId(idPrefix + "Phonetic");
        partOfSpeech.setId(idPrefix + "Pos");
        example.setId(idPrefix + "Example");
        note.setId(idPrefix + "Note");
        tags.setId(idPrefix + "Tags");
        synonyms.setId(idPrefix + "Synonyms");
        antonyms.setId(idPrefix + "Antonyms");
        playButton.setId(idPrefix + "PlayButton");
        extrasLink.setId(idPrefix + "ExtrasLink");
        extrasStatus.setId(idPrefix + "ExtrasStatus");
        empty.setId(idPrefix + "Empty");
        playButton.setAccessibleText(tr("extras.play"));
        playButton.setTooltip(new Tooltip(tr("extras.play")));
        playButton.getStyleClass().add("play-button");
        extrasLink.setTooltip(new Tooltip(tr("extras.lookUp.tooltip")));
        extrasStatus.setWrapText(true);
        extrasStatus.getStyleClass().add("muted-text");

        title.getStyleClass().add("details-title");
        title.setWrapText(true);
        empty.setWrapText(true);
        empty.getStyleClass().add("muted-text");
        note.setMaxHeight(NOTE_MAX_HEIGHT);

        HBox pronunciation = new HBox(8, phonetic, playButton);
        pronunciation.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(phonetic, Priority.NEVER);
        addRow(tr("word.phonetic"), pronunciation);
        addRow(tr("word.partOfSpeech"), partOfSpeech);
        addRow(tr("word.example"), example);
        addRow(tr("word.note"), note);
        addRow(tr("word.tags"), tags);
        addRow(tr("word.synonyms"), synonyms);
        addRow(tr("word.antonyms"), antonyms);
        HBox extrasRow = new HBox(8, extrasLink, extrasStatus);
        extrasRow.setAlignment(Pos.CENTER_LEFT);
        extrasLink.managedProperty().bind(extrasLink.visibleProperty());
        extrasStatus.managedProperty().bind(extrasStatus.visibleProperty());
        extrasRow.managedProperty().bind(extrasLink.visibleProperty().or(extrasStatus.visibleProperty()));
        extrasRow.visibleProperty().bind(extrasRow.managedProperty());

        root.getChildren().addAll(title, rowBox, extrasRow, empty);
        root.setPadding(new Insets(10, 14, 10, 14));
        root.getStyleClass().add("details-card");
        show(title, false);
        show(empty, false);
        showExtras(WordExtras.NONE, false, false, false, "");
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
        phoneticShown = !details.phonetic().isEmpty();
        show(phonetic, phoneticShown);
        showRow(0, phoneticShown || playButton.isVisible());
        showRow(1, !details.partOfSpeech().isEmpty());
        showRow(2, !details.example().isEmpty());
        showRow(3, !details.note().isEmpty());
        showRow(4, !details.tags().isEmpty());
        empty.setText(emptyText == null ? "" : emptyText);
        show(empty, details.isEmpty() && !empty.getText().isEmpty());
    }

    /**
     * Shows what an online dictionary knows about the word besides its meaning: its synonyms and
     * antonyms, each row only when known, and the play button when it has a recording.
     *
     * @param playable    whether the play button is enabled (it is disabled while offline mode is on)
     * @param canLookUp   whether the link that looks the word up for them is offered
     * @param status      a line about the lookup or the recording, e.g. "Looking up..."; empty for none
     */
    public void showExtras(WordExtras extras, boolean playable, boolean canLookUp, boolean busy, String status) {
        synonyms.setText(String.join(", ", extras.synonyms()));
        antonyms.setText(String.join(", ", extras.antonyms()));
        showRow(5, !extras.synonyms().isEmpty());
        showRow(6, !extras.antonyms().isEmpty());
        show(playButton, extras.hasAudio());
        playButton.setDisable(!playable || busy);
        showRow(0, phoneticShown || extras.hasAudio());
        extrasLink.setVisible(canLookUp);
        extrasLink.setDisable(busy);
        extrasStatus.setText(status == null ? "" : status);
        extrasStatus.setVisible(!extrasStatus.getText().isEmpty());
    }

    /** What the play button does. */
    public void setOnPlay(Runnable action) {
        playButton.setOnAction(event -> action.run());
    }

    /** What the link that looks the word up for its synonyms and recording does. */
    public void setOnLookUp(Runnable action) {
        extrasLink.setOnAction(event -> {
            extrasLink.setVisited(false);
            action.run();
        });
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
        nameLabel.getStyleClass().add("details-name");
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
