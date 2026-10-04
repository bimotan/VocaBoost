package com.vocabtrainer.ui.importing;

import com.vocabtrainer.service.ImportPreview;
import com.vocabtrainer.service.csv.WordColumn;
import com.vocabtrainer.service.csv.WordColumns;
import com.vocabtrainer.service.wordlist.WordListFile;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import static com.vocabtrainer.util.Messages.tr;

/**
 * The column mapping of the import preview: which column of the file holds the English word, the
 * meaning, the example, the tags, the phonetic, the part of speech and the note, pre-selected as the
 * import detected them, and the first rows as they would be imported. Choosing another column
 * calls {@code onChange}, which previews the file again with the chosen columns.
 */
final class ColumnMappingPane {
    /** The fields in the order the pane lists them. */
    private static final List<WordColumn> FIELDS = List.of(WordColumn.ENGLISH, WordColumn.CHINESE,
        WordColumn.EXAMPLE, WordColumn.TAGS, WordColumn.PHONETIC, WordColumn.POS, WordColumn.NOTE);

    /** A choice in a column combo box: a column of the file, or {@link #NONE}. */
    record ColumnChoice(int index, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    /** No column of the file holds the field. */
    private final ColumnChoice none = new ColumnChoice(-1, tr("import.mapping.none"));
    private final Map<WordColumn, ComboBox<ColumnChoice>> comboBoxes = new LinkedHashMap<>();
    private final Label formatLabel = new Label();
    private final Label chineseHint = new Label();
    private final TableView<ImportPreview.Row> table = new TableView<>();
    private final VBox root;
    private boolean showing;

    ColumnMappingPane(Runnable onChange) {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        int position = 0;
        for (WordColumn field : FIELDS) {
            ComboBox<ColumnChoice> comboBox = new ComboBox<>();
            comboBox.setId("map" + idPart(field) + "Column");
            comboBox.setMaxWidth(Double.MAX_VALUE);
            comboBox.valueProperty().addListener((observable, previous, chosen) -> {
                if (!showing && chosen != null) {
                    onChange.run();
                }
            });
            comboBoxes.put(field, comboBox);
            Label label = new Label(fieldLabel(field));
            label.setLabelFor(comboBox);
            int row = position / 2;
            int column = (position % 2) * 2;
            grid.add(label, column, row);
            grid.add(comboBox, column + 1, row);
            position++;
        }
        for (int i = 0; i < 4; i++) {
            ColumnConstraints constraints = new ColumnConstraints();
            if (i % 2 == 1) {
                constraints.setHgrow(Priority.ALWAYS);
                constraints.setPrefWidth(280);
            }
            grid.getColumnConstraints().add(constraints);
        }

        formatLabel.setId("importFormatLabel");
        formatLabel.setWrapText(true);
        chineseHint.setId("importMeaningHint");
        chineseHint.setWrapText(true);
        table.setId("importPreviewTable");
        table.setAccessibleText(tr("import.mapping.table.accessible"));
        table.setPrefHeight(250);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(tr("import.mapping.table.empty")));
        table.getColumns().add(column(tr("import.mapping.column.line"), 45, row -> String.valueOf(row.line())));
        table.getColumns().add(column(tr("import.mapping.column.english"), 110, ImportPreview.Row::english));
        table.getColumns().add(column(tr("import.mapping.column.chinese"), 150, ImportPreview.Row::chinese));
        table.getColumns().add(column(tr("import.mapping.column.pos"), 65, ImportPreview.Row::partOfSpeech));
        table.getColumns().add(column(tr("word.phonetic"), 80, ImportPreview.Row::phonetic));
        table.getColumns().add(column(tr("word.example"), 140, ImportPreview.Row::example));
        table.getColumns().add(column(tr("word.note"), 100, ImportPreview.Row::note));
        table.getColumns().add(column(tr("word.tags"), 70, ImportPreview.Row::tags));
        table.getColumns().add(column(tr("import.mapping.column.status"), 270, ImportPreview.Row::status));

        Label title = new Label(tr("import.mapping.title"));
        title.setWrapText(true);
        root = new VBox(8, formatLabel, title, grid, chineseHint, table);
        root.setId("columnMappingPane");
        hide();
    }

    Node root() {
        return root;
    }

    boolean isShown() {
        return root.isVisible();
    }

    /**
     * Shows a preview; with {@code selectColumns} the combo boxes are set to the preview's columns
     * (a new file), otherwise they keep the user's choice.
     */
    void show(ImportPreview preview, boolean selectColumns) {
        showing = true;
        try {
            if (selectColumns) {
                List<ColumnChoice> choices = new ArrayList<>();
                choices.add(none);
                for (WordListFile.FileColumn column : preview.fileColumns()) {
                    choices.add(new ColumnChoice(column.index(), column.label()));
                }
                for (Map.Entry<WordColumn, ComboBox<ColumnChoice>> entry : comboBoxes.entrySet()) {
                    ComboBox<ColumnChoice> comboBox = entry.getValue();
                    comboBox.getItems().setAll(choices);
                    int index = preview.mapping().index(entry.getKey());
                    comboBox.getSelectionModel().select(choices.stream()
                        .filter(choice -> choice.index() == index)
                        .findFirst()
                        .orElse(none));
                }
            }
            int width = preview.fileColumns().size();
            String file = tr("import.mapping.file", preview.encoding(), width == 1 ? tr("import.mapping.oneColumn")
                : tr("import.mapping.columns", preview.delimiter(), width));
            formatLabel.setText(preview.format().isBlank() ? file : tr("import.mapping.fileFormat", file, preview.format()));
            chineseHint.setText(preview.mapping().has(WordColumn.CHINESE) && preview.needMeaningCount() == 0
                ? "" : tr("import.mapping.meaningHint"));
            chineseHint.setVisible(!chineseHint.getText().isEmpty());
            chineseHint.setManaged(chineseHint.isVisible());
            table.getItems().setAll(preview.rows());
            root.setVisible(true);
            root.setManaged(true);
        } finally {
            showing = false;
        }
    }

    void hide() {
        root.setVisible(false);
        root.setManaged(false);
    }

    /** The columns chosen in the combo boxes. */
    WordColumns chosenColumns() {
        WordColumns columns = WordColumns.none();
        for (Map.Entry<WordColumn, ComboBox<ColumnChoice>> entry : comboBoxes.entrySet()) {
            ColumnChoice choice = entry.getValue().getValue();
            columns = columns.with(entry.getKey(), choice == null ? -1 : choice.index());
        }
        return columns;
    }

    private static String fieldLabel(WordColumn field) {
        return switch (field) {
            case ENGLISH -> tr("import.mapping.field.english");
            case CHINESE -> tr("add.prompt.chinese");
            case EXAMPLE -> tr("word.example");
            case TAGS -> tr("word.tags");
            case PHONETIC -> tr("word.phonetic");
            case POS -> tr("word.partOfSpeech");
            case NOTE -> tr("word.note");
        };
    }

    private static String idPart(WordColumn field) {
        String name = field.name().toLowerCase(Locale.ROOT);
        return field == WordColumn.POS ? "Pos" : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static TableColumn<ImportPreview.Row, String> column(String title, double width,
                                                                 Function<ImportPreview.Row, String> value) {
        TableColumn<ImportPreview.Row, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        return column;
    }
}
