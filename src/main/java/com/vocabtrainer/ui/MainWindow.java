package com.vocabtrainer.ui;

import com.vocabtrainer.domain.Achievement;
import com.vocabtrainer.domain.DailyGoalProgress;
import com.vocabtrainer.domain.DailyReviewStat;
import com.vocabtrainer.service.DashboardStats;
import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.DictionaryEntry;
import com.vocabtrainer.domain.DictionaryLookupResult;
import com.vocabtrainer.domain.GoalUpdate;
import com.vocabtrainer.domain.HardWordStat;
import com.vocabtrainer.domain.MemoryBucketStat;
import com.vocabtrainer.domain.ReviewOutcome;
import com.vocabtrainer.domain.ReviewMode;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.ReviewSessionSummary;
import com.vocabtrainer.domain.ValidatedWord;
import com.vocabtrainer.domain.WordVerificationResult;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.AiCacheRepository;
import com.vocabtrainer.repository.DictionaryCacheRepository;
import com.vocabtrainer.repository.WordRepository;
import com.vocabtrainer.service.AchievementService;
import com.vocabtrainer.service.AiService;
import com.vocabtrainer.service.AiServiceFactory;
import com.vocabtrainer.service.BackupService;
import com.vocabtrainer.service.DeckService;
import com.vocabtrainer.service.DictionaryService;
import com.vocabtrainer.service.DictionaryServiceFactory;
import com.vocabtrainer.service.GoalService;
import com.vocabtrainer.service.ImportExportService;
import com.vocabtrainer.service.ImportResult;
import com.vocabtrainer.service.LocalDictionaryService;
import com.vocabtrainer.service.LocalDictionaryStatus;
import com.vocabtrainer.service.ReviewAnswer;
import com.vocabtrainer.service.ReviewService;
import com.vocabtrainer.service.SettingsService;
import com.vocabtrainer.service.StatsService;
import com.vocabtrainer.service.WordValidationService;
import com.vocabtrainer.util.DateTimeUtil;
import javafx.beans.property.SimpleStringProperty;
import javafx.animation.PauseTransition;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class MainWindow {
    private record DeckRow(Deck deck, String name, int words, int due, String latestReview) {
    }

    private Deck currentDeck;
    private final DeckService deckService;
    private final WordRepository wordRepository;
    private final ReviewService reviewService;
    private final ImportExportService importExportService;
    private final StatsService statsService;
    private final GoalService goalService;
    private final AchievementService achievementService;
    private DictionaryService dictionaryService;
    private final DictionaryCacheRepository dictionaryCacheRepository;
    private final SettingsService settingsService;
    private final WordValidationService validationService;
    private final BackupService backupService;
    private AiService aiService;
    private final AiCacheRepository aiCacheRepository;
    private final Path databasePath;

    private final ObservableList<WordCard> wordItems = FXCollections.observableArrayList();
    private final ObservableList<DeckRow> deckRows = FXCollections.observableArrayList();
    private final ObservableList<DeckRow> archivedDeckRows = FXCollections.observableArrayList();
    private final Label totalWordsLabel = new Label("-");
    private final Label dueTodayLabel = new Label("-");
    private final Label reviewedTodayLabel = new Label("-");
    private final Label newWordsTodayLabel = new Label("-");
    private final Label accuracyTodayLabel = new Label("-");
    private final Label masteredWordsLabel = new Label("-");
    private final Label streakLabel = new Label("-");
    private final Label xpLabel = new Label("-");
    private final Label badgesLabel = new Label("-");
    private final Label databasePathLabel = new Label();
    private final Label headerSubtitleLabel = new Label();
    private final ProgressBar reviewProgress = new ProgressBar(0);
    private final ProgressBar newWordProgress = new ProgressBar(0);

    private TableView<WordCard> wordTable;
    private TableView<DeckRow> deckTable;
    private TableView<DeckRow> archivedDeckTable;
    private ComboBox<Deck> addDeckSelector;
    private TextField searchField;
    private ComboBox<String> wordStatusFilter;
    private TextField tagFilterField;
    private TextField posFilterField;
    private Label reviewWordLabel;
    private Label reviewMetaLabel;
    private TextField answerField;
    private TextArea reviewResultArea;
    private VBox completionCard;
    private Label completionTitleLabel;
    private Label completionMetricsLabel;
    private Button submitAnswerButton;
    private HBox ratingButtons;
    private WordCard currentReviewWord;
    private ComboBox<ReviewMode> reviewModeSelector;
    private ComboBox<String> sessionSizeSelector;
    private TextField customSessionSizeField;
    private Label sessionProgressLabel;

    private BarChart<String, Number> reviewCountChart;
    private LineChart<String, Number> accuracyChart;
    private PieChart memoryChart;
    private TextArea hardestWordsArea;
    private TextArea analyticsArea;
    private Label overdueStatsLabel;
    private ComboBox<Deck> deckSelector;
    private boolean statisticsSelected;

    public MainWindow(Deck deck, DeckService deckService, WordRepository wordRepository, ReviewService reviewService,
                      ImportExportService importExportService, StatsService statsService,
                      GoalService goalService, AchievementService achievementService,
                      DictionaryService dictionaryService, DictionaryCacheRepository dictionaryCacheRepository,
                      AiCacheRepository aiCacheRepository, SettingsService settingsService, WordValidationService validationService,
                      BackupService backupService, AiService aiService, Path databasePath) {
        this.currentDeck = deck;
        this.deckService = deckService;
        this.wordRepository = wordRepository;
        this.reviewService = reviewService;
        this.importExportService = importExportService;
        this.statsService = statsService;
        this.goalService = goalService;
        this.achievementService = achievementService;
        this.dictionaryService = dictionaryService;
        this.dictionaryCacheRepository = dictionaryCacheRepository;
        this.aiCacheRepository = aiCacheRepository;
        this.settingsService = settingsService;
        this.validationService = validationService;
        this.backupService = backupService;
        this.aiService = aiService;
        this.databasePath = databasePath;
    }

    public Scene createScene() {
        BorderPane root = new BorderPane();
        root.setTop(createHeader());

        TabPane tabs = new TabPane();
        tabs.getTabs().add(createDashboardTab());
        tabs.getTabs().add(createDecksTab());
        tabs.getTabs().add(createReviewTab());
        tabs.getTabs().add(createAddImportTab());
        tabs.getTabs().add(createStatisticsTab());
        tabs.getTabs().add(createWordListTab());
        root.setCenter(tabs);

        refreshAll();
        loadNextReviewWord();
        return new Scene(root, 1120, 780);
    }

    private VBox createHeader() {
        Label title = new Label("VocaBoost");
        title.setStyle("-fx-font-size: 24px; -fx-font-weight: 700;");
        headerSubtitleLabel.setStyle("-fx-text-fill: #4b5563;");

        deckSelector = new ComboBox<>();
        deckSelector.setPrefWidth(220);
        deckSelector.setCellFactory(list -> deckCell());
        deckSelector.setButtonCell(deckCell());
        deckSelector.valueProperty().addListener((observable, oldDeck, newDeck) -> {
            if (newDeck != null && (currentDeck == null || newDeck.getId() != currentDeck.getId())) {
                currentDeck = newDeck;
                onDeckChanged();
            }
        });

        Button newDeckButton = new Button("New deck");
        newDeckButton.setOnAction(event -> createDeck());
        Button renameDeckButton = new Button("Rename");
        renameDeckButton.setOnAction(event -> renameCurrentDeck());
        Button archiveDeckButton = new Button("Archive");
        archiveDeckButton.setOnAction(event -> archiveCurrentDeck());

        HBox deckControls = new HBox(8, new Label("Deck"), deckSelector, newDeckButton, renameDeckButton, archiveDeckButton);
        deckControls.setAlignment(Pos.CENTER_LEFT);
        HBox headerLine = new HBox(24, new VBox(4, title, headerSubtitleLabel), deckControls);
        headerLine.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(headerLine.getChildren().get(0), Priority.ALWAYS);
        refreshDeckSelector();
        updateHeaderSubtitle();
        VBox box = new VBox(4, headerLine);
        box.setPadding(new Insets(18, 24, 12, 24));
        box.setStyle("-fx-background-color: #f8fafc; -fx-border-color: #e5e7eb; -fx-border-width: 0 0 1 0;");
        return box;
    }

    private ListCell<Deck> deckCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(Deck item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getName());
            }
        };
    }

    private void updateHeaderSubtitle() {
        String dictionaryStatus = settingsService.getEcdictPath().isPresent()
            ? "ECDICT configured"
            : "starter/online fallback";
        String deckName = currentDeck == null ? "-" : currentDeck.getName();
        headerSubtitleLabel.setText("Deck: " + deckName + " | Dictionary: " + dictionaryStatus
            + " | AI: " + (aiService.isAvailable() ? "configured" : "mock"));
    }

    private void refreshDeckSelector() {
        if (deckSelector == null) {
            return;
        }
        List<Deck> decks = deckService.activeDecks();
        deckSelector.getItems().setAll(decks);
        Deck selected = decks.stream()
            .filter(item -> currentDeck != null && item.getId() == currentDeck.getId())
            .findFirst()
            .orElse(decks.isEmpty() ? null : decks.get(0));
        if (selected != null) {
            currentDeck = selected;
            deckSelector.getSelectionModel().select(selected);
        }
        refreshAddDeckSelector();
    }

    private void refreshAddDeckSelector() {
        if (addDeckSelector == null) {
            return;
        }
        List<Deck> decks = deckService.activeDecks();
        addDeckSelector.getItems().setAll(decks);
        Deck selected = decks.stream()
            .filter(item -> currentDeck != null && item.getId() == currentDeck.getId())
            .findFirst()
            .orElse(decks.isEmpty() ? null : decks.get(0));
        if (selected != null) {
            addDeckSelector.getSelectionModel().select(selected);
        }
    }

    private void createDeck() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("New deck");
        dialog.setHeaderText("Create a new deck");
        dialog.setContentText("Deck name");
        dialog.showAndWait().ifPresent(name -> {
            try {
                currentDeck = deckService.createDeck(name);
                refreshDeckSelector();
                onDeckChanged();
            } catch (RuntimeException e) {
                showError("Create deck failed", rootMessage(e));
            }
        });
    }

    private void renameCurrentDeck() {
        if (currentDeck == null) {
            return;
        }
        TextInputDialog dialog = new TextInputDialog(currentDeck.getName());
        dialog.setTitle("Rename deck");
        dialog.setHeaderText("Rename current deck");
        dialog.setContentText("Deck name");
        dialog.showAndWait().ifPresent(name -> {
            try {
                currentDeck = deckService.renameDeck(currentDeck.getId(), name);
                refreshDeckSelector();
                onDeckChanged();
            } catch (RuntimeException e) {
                showError("Rename deck failed", rootMessage(e));
            }
        });
    }

    private void archiveCurrentDeck() {
        if (currentDeck == null) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Archive deck");
        confirm.setHeaderText("Archive " + currentDeck.getName() + "?");
        confirm.setContentText("Words remain in SQLite, but the deck will be hidden from active study views.");
        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            try {
                currentDeck = deckService.archiveDeck(currentDeck.getId());
                refreshDeckSelector();
                onDeckChanged();
            } catch (RuntimeException e) {
                showError("Archive deck failed", rootMessage(e));
            }
        }
    }

    private void restoreSelectedArchivedDeck() {
        if (archivedDeckTable == null) {
            return;
        }
        DeckRow selected = archivedDeckTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showInfo("Please select an archived deck to restore.");
            return;
        }
        try {
            currentDeck = deckService.restoreDeck(selected.deck().getId());
            refreshDeckSelector();
            onDeckChanged();
        } catch (RuntimeException e) {
            showError("Restore deck failed", rootMessage(e));
        }
    }

    private void onDeckChanged() {
        updateHeaderSubtitle();
        currentReviewWord = null;
        reviewService.resetSession(currentDeck.getId());
        refreshAddDeckSelector();
        refreshAll();
        loadNextReviewWord();
    }

    private Tab createDashboardTab() {
        GridPane grid = new GridPane();
        grid.setPadding(new Insets(24));
        grid.setHgap(18);
        grid.setVgap(14);

        addStat(grid, 0, "Total words", totalWordsLabel);
        addStat(grid, 1, "Due now", dueTodayLabel);
        addStat(grid, 2, "Reviews today", reviewedTodayLabel);
        addStat(grid, 3, "New words today", newWordsTodayLabel);
        addStat(grid, 4, "Accuracy today", accuracyTodayLabel);
        addStat(grid, 5, "Mastered words", masteredWordsLabel);
        addStat(grid, 6, "Streak", streakLabel);
        addStat(grid, 7, "XP", xpLabel);

        reviewProgress.setPrefWidth(420);
        newWordProgress.setPrefWidth(420);
        badgesLabel.setWrapText(true);
        databasePathLabel.setStyle("-fx-text-fill: #6b7280;");
        Button refreshButton = new Button("Refresh");
        refreshButton.setOnAction(event -> refreshAll());

        VBox progressBox = new VBox(10,
            sectionTitle("Daily Goals"),
            new Label("Review goal"),
            reviewProgress,
            new Label("New-word goal"),
            newWordProgress,
            sectionTitle("Unlocked Badges"),
            badgesLabel,
            databasePathLabel,
            refreshButton
        );
        progressBox.setPadding(new Insets(18, 0, 0, 0));
        grid.add(progressBox, 0, 8, 2, 1);

        Tab tab = new Tab("Dashboard", grid);
        tab.setClosable(false);
        return tab;
    }

    private void addStat(GridPane grid, int row, String name, Label valueLabel) {
        Label nameLabel = new Label(name);
        nameLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #4b5563;");
        valueLabel.setStyle("-fx-font-size: 22px; -fx-font-weight: 700;");
        grid.add(nameLabel, 0, row);
        grid.add(valueLabel, 1, row);
    }

    private Tab createDecksTab() {
        deckTable = new TableView<>(deckRows);
        deckTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        configureDeckTable(deckTable);

        archivedDeckTable = new TableView<>(archivedDeckRows);
        archivedDeckTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        configureDeckTable(archivedDeckTable);
        archivedDeckTable.setPrefHeight(180);

        Button switchButton = new Button("Switch selected");
        switchButton.setOnAction(event -> {
            DeckRow selected = deckTable.getSelectionModel().getSelectedItem();
            if (selected != null) {
                currentDeck = selected.deck();
                refreshDeckSelector();
                onDeckChanged();
            }
        });
        Button restoreButton = new Button("Restore selected archived deck");
        restoreButton.setOnAction(event -> restoreSelectedArchivedDeck());
        Button refreshButton = new Button("Refresh");
        refreshButton.setOnAction(event -> refreshDeckTable());
        VBox content = new VBox(12,
            sectionTitle("Active Decks"),
            deckTable,
            new HBox(10, switchButton, refreshButton),
            sectionTitle("Archived Decks"),
            archivedDeckTable,
            restoreButton
        );
        content.setPadding(new Insets(24));
        VBox.setVgrow(deckTable, Priority.ALWAYS);
        Tab tab = new Tab("Decks", content);
        tab.setClosable(false);
        return tab;
    }

    private void configureDeckTable(TableView<DeckRow> table) {
        TableColumn<DeckRow, String> nameCol = new TableColumn<>("Deck");
        nameCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().name()));
        TableColumn<DeckRow, String> wordsCol = new TableColumn<>("Words");
        wordsCol.setCellValueFactory(data -> new SimpleStringProperty(String.valueOf(data.getValue().words())));
        TableColumn<DeckRow, String> dueCol = new TableColumn<>("Due");
        dueCol.setCellValueFactory(data -> new SimpleStringProperty(String.valueOf(data.getValue().due())));
        TableColumn<DeckRow, String> latestCol = new TableColumn<>("Latest review");
        latestCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().latestReview()));
        table.getColumns().addAll(nameCol, wordsCol, dueCol, latestCol);
    }

    private Tab createReviewTab() {
        reviewModeSelector = new ComboBox<>();
        reviewModeSelector.getItems().setAll(ReviewMode.values());
        reviewModeSelector.setCellFactory(list -> reviewModeCell());
        reviewModeSelector.setButtonCell(reviewModeCell());
        reviewModeSelector.getSelectionModel().select(ReviewMode.EN_TO_ZH);
        reviewModeSelector.valueProperty().addListener((observable, oldMode, newMode) -> {
            reviewService.resetSession(currentDeck.getId());
            loadNextReviewWord();
        });
        sessionSizeSelector = new ComboBox<>();
        sessionSizeSelector.getItems().setAll("10", "20", "50", "All Due", "Custom");
        sessionSizeSelector.getSelectionModel().select("20");
        customSessionSizeField = new TextField();
        customSessionSizeField.setPromptText("Custom");
        customSessionSizeField.setPrefWidth(90);
        customSessionSizeField.setDisable(true);
        sessionSizeSelector.valueProperty().addListener((observable, oldValue, newValue) ->
            customSessionSizeField.setDisable(!"Custom".equals(newValue)));
        Button startSessionButton = new Button("Start Session");
        startSessionButton.setOnAction(event -> startReviewSession());
        Button resetSessionButton = new Button("Reset Session");
        resetSessionButton.setOnAction(event -> {
            reviewService.resetSession(currentDeck.getId());
            loadNextReviewWord();
        });
        sessionProgressLabel = new Label();
        sessionProgressLabel.setStyle("-fx-text-fill: #4b5563;");
        HBox modeBox = new HBox(10, new Label("Mode"), reviewModeSelector, sessionProgressLabel);
        modeBox.setAlignment(Pos.CENTER_LEFT);
        HBox sessionBox = new HBox(10, new Label("Session size"), sessionSizeSelector, customSessionSizeField,
            startSessionButton, resetSessionButton);
        sessionBox.setAlignment(Pos.CENTER_LEFT);

        reviewWordLabel = new Label("Loading...");
        reviewWordLabel.setStyle("-fx-font-size: 34px; -fx-font-weight: 700;");
        reviewMetaLabel = new Label();
        reviewMetaLabel.setStyle("-fx-text-fill: #4b5563;");
        answerField = new TextField();
        answerField.setPromptText("Enter Chinese meaning");
        answerField.setPrefWidth(420);
        submitAnswerButton = new Button("Submit");
        submitAnswerButton.setOnAction(event -> submitCurrentAnswer());
        answerField.setOnAction(event -> submitCurrentAnswer());

        reviewResultArea = new TextArea();
        reviewResultArea.setEditable(false);
        reviewResultArea.setWrapText(true);
        reviewResultArea.setPrefRowCount(8);

        completionTitleLabel = new Label("Review complete");
        completionTitleLabel.setStyle("-fx-font-size: 22px; -fx-font-weight: 700;");
        completionMetricsLabel = new Label();
        completionMetricsLabel.setWrapText(true);
        completionCard = new VBox(8, completionTitleLabel, completionMetricsLabel);
        completionCard.setPadding(new Insets(16));
        completionCard.setStyle("-fx-background-color: #ecfdf5; -fx-border-color: #10b981; -fx-border-radius: 6; -fx-background-radius: 6;");
        completionCard.setVisible(false);
        completionCard.setManaged(false);

        ratingButtons = new HBox(10,
            ratingButton(ReviewRating.AGAIN),
            ratingButton(ReviewRating.HARD),
            ratingButton(ReviewRating.GOOD),
            ratingButton(ReviewRating.EASY)
        );
        ratingButtons.setDisable(true);

        HBox answerBox = new HBox(10, answerField, submitAnswerButton);
        answerBox.setAlignment(Pos.CENTER_LEFT);
        VBox content = new VBox(16, modeBox, sessionBox, reviewWordLabel, reviewMetaLabel, answerBox,
            completionCard, reviewResultArea, ratingButtons);
        content.setPadding(new Insets(28));
        VBox.setVgrow(reviewResultArea, Priority.ALWAYS);

        Tab tab = new Tab("Review", content);
        tab.setClosable(false);
        return tab;
    }

    private ListCell<ReviewMode> reviewModeCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(ReviewMode item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getLabel());
            }
        };
    }

    private Button ratingButton(ReviewRating rating) {
        Button button = new Button(rating.getLabel());
        button.setMinWidth(90);
        button.setOnAction(event -> rateCurrentWord(rating));
        return button;
    }

    private Tab createAddImportTab() {
        TextField englishField = new TextField();
        englishField.setPromptText("English");
        TextField chineseField = new TextField();
        chineseField.setPromptText("Chinese meaning");
        TextField phoneticField = new TextField();
        phoneticField.setPromptText("Phonetic");
        TextField posField = new TextField();
        posField.setPromptText("Part of speech");
        TextField tagsField = new TextField();
        tagsField.setPromptText("Tags");
        TextArea exampleArea = new TextArea();
        exampleArea.setPromptText("Example sentence");
        exampleArea.setPrefRowCount(2);
        TextArea noteArea = new TextArea();
        noteArea.setPromptText("Notes");
        noteArea.setPrefRowCount(3);
        Label addStatus = new Label();
        addStatus.setWrapText(true);
        addDeckSelector = new ComboBox<>();
        addDeckSelector.setPrefWidth(260);
        addDeckSelector.setCellFactory(list -> deckCell());
        addDeckSelector.setButtonCell(deckCell());
        refreshAddDeckSelector();

        Button addButton = new Button("Add word");
        addButton.setOnAction(event -> addWordFromForm(
            englishField, chineseField, phoneticField, posField, exampleArea, noteArea, tagsField, addStatus));

        GridPane addForm = new GridPane();
        addForm.setHgap(10);
        addForm.setVgap(10);
        addForm.add(new Label("Add to deck"), 0, 0);
        addForm.add(addDeckSelector, 1, 0);
        addForm.add(new Label("English"), 0, 1);
        addForm.add(englishField, 1, 1);
        addForm.add(new Label("Chinese"), 0, 2);
        addForm.add(chineseField, 1, 2);
        addForm.add(new Label("Phonetic"), 0, 3);
        addForm.add(phoneticField, 1, 3);
        addForm.add(new Label("POS"), 0, 4);
        addForm.add(posField, 1, 4);
        addForm.add(new Label("Tags"), 0, 5);
        addForm.add(tagsField, 1, 5);
        addForm.add(new Label("Example"), 0, 6);
        addForm.add(exampleArea, 1, 6);
        addForm.add(new Label("Notes"), 0, 7);
        addForm.add(noteArea, 1, 7);
        addForm.add(addButton, 1, 8);
        addForm.add(addStatus, 1, 9);

        VBox dictionaryBox = createDictionaryBox(englishField, chineseField, phoneticField, posField,
            exampleArea, noteArea, tagsField);
        VBox ecdictBox = createEcdictSettingsBox();
        VBox aiBox = createAiSettingsBox();
        VBox importBox = createImportBox();

        VBox content = new VBox(24, sectionTitle("Manual Add"), addForm, dictionaryBox, ecdictBox, aiBox, importBox);
        content.setPadding(new Insets(24));
        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        Tab tab = new Tab("Add / Import", scrollPane);
        tab.setClosable(false);
        return tab;
    }

    private VBox createDictionaryBox(TextField englishField, TextField chineseField, TextField phoneticField,
                                     TextField posField, TextArea exampleArea, TextArea noteArea,
                                     TextField tagsField) {
        TextField lookupField = new TextField();
        lookupField.setPromptText("Enter an English word to look up");
        Button lookupButton = new Button("Lookup online");
        Button refreshLookupButton = new Button("Refresh cache");
        Label lookupStatus = new Label();
        lookupStatus.setWrapText(true);
        ListView<DictionaryEntry> results = new ListView<>();
        results.setPrefHeight(120);
        results.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(DictionaryEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    String meaning = item.chinese() == null || item.chinese().isBlank()
                        ? "English definition: " + item.definition()
                        : item.chinese();
                    setText(item.english() + " | " + meaning + " | " + item.source());
                }
            }
        });
        results.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, entry) -> {
            if (entry != null) {
                englishField.setText(entry.english());
                chineseField.setText(entry.chinese() == null ? "" : entry.chinese());
                phoneticField.setText(entry.phonetic());
                posField.setText(entry.partOfSpeech());
                exampleArea.setText(entry.example());
                noteArea.setText(dictionaryNote(entry));
                tagsField.setText(appendTag("dictionary", entry.source()));
            }
        });
        lookupButton.setOnAction(event -> runDictionaryLookup(lookupField, lookupButton, lookupStatus, results, false));
        refreshLookupButton.setOnAction(event -> runDictionaryLookup(lookupField, refreshLookupButton, lookupStatus, results, true));
        HBox controls = new HBox(10, lookupField, lookupButton, refreshLookupButton);
        HBox.setHgrow(lookupField, Priority.ALWAYS);
        return new VBox(10, sectionTitle("Dictionary Lookup"), controls, results, lookupStatus);
    }

    private void runDictionaryLookup(TextField lookupField, Button actionButton, Label lookupStatus,
                                     ListView<DictionaryEntry> results, boolean refresh) {
        try {
            String english = validationService.validateEnglishOnly(lookupField.getText());
            actionButton.setDisable(true);
            runBackground(
                () -> refresh ? dictionaryService.refresh(english) : dictionaryService.lookup(english),
                result -> {
                    lookupStatus.setText(result.success()
                        ? result.message()
                        : "词条未找到。" + System.lineSeparator() + result.message());
                    results.getItems().setAll(result.entries());
                    if (!result.entries().isEmpty()) {
                        results.getSelectionModel().selectFirst();
                    }
                    actionButton.setDisable(false);
                },
                error -> {
                    lookupStatus.setText("查词失败：" + rootMessage(error));
                    actionButton.setDisable(false);
                },
                lookupStatus,
                refresh ? "Refreshing dictionary cache..." : "Looking up..."
            );
        } catch (IllegalArgumentException e) {
            lookupStatus.setText(e.getMessage());
        }
    }

    private VBox createEcdictSettingsBox() {
        TextField pathField = new TextField(settingsService.getEcdictPath().orElse(""));
        pathField.setPromptText("Choose local ECDICT CSV");
        Label statusLabel = new Label(new LocalDictionaryService(pathField.getText()).status().toDisplayText());
        statusLabel.setWrapText(true);

        Button chooseButton = new Button("Choose ECDICT CSV");
        chooseButton.setOnAction(event -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Choose ECDICT CSV");
            chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("CSV", "*.csv"),
                new FileChooser.ExtensionFilter("All Files", "*.*")
            );
            java.io.File file = chooser.showOpenDialog(chooseButton.getScene().getWindow());
            if (file != null) {
                pathField.setText(file.toPath().toString());
            }
        });

        Button testButton = new Button("Test ECDICT");
        testButton.setOnAction(event -> {
            LocalDictionaryStatus status = new LocalDictionaryService(pathField.getText()).status();
            statusLabel.setText(status.toDisplayText()
                + (status.configuredPathLoaded() ? System.lineSeparator() + "Configured CSV loaded." : ""));
        });

        Button saveButton = new Button("Save Dictionary Path");
        saveButton.setOnAction(event -> {
            settingsService.saveEcdictPath(pathField.getText());
            LocalDictionaryService local = new LocalDictionaryService(pathField.getText());
            LocalDictionaryStatus status = local.status();
            settingsService.save(SettingsService.ECDICT_LAST_LOADED_COUNT_KEY, String.valueOf(status.loadedCount()));
            settingsService.save(SettingsService.ECDICT_LAST_LOADED_AT_KEY, LocalDateTime.now().toString());
            reloadDictionaryService();
            statusLabel.setText("Saved. " + status.toDisplayText());
        });

        Button clearButton = new Button("Clear Dictionary Path");
        clearButton.setOnAction(event -> {
            settingsService.clearEcdictPath();
            pathField.clear();
            reloadDictionaryService();
            statusLabel.setText("Cleared. Using bundled starter and online fallback.");
        });

        HBox controls = new HBox(10, pathField, chooseButton, testButton, saveButton, clearButton);
        HBox.setHgrow(pathField, Priority.ALWAYS);
        return new VBox(10, sectionTitle("ECDICT Local Dictionary"), controls, statusLabel);
    }

    private VBox createAiSettingsBox() {
        TextField providerField = new TextField(settingsService.getAiProvider().orElse("openai-compatible"));
        providerField.setPromptText("openai-compatible");
        TextField baseUrlField = new TextField(settingsService.getAiBaseUrl().orElse(""));
        baseUrlField.setPromptText("https://your-provider.example/v1/chat/completions");
        PasswordField apiKeyField = new PasswordField();
        apiKeyField.setText(settingsService.getAiApiKey().orElse(""));
        apiKeyField.setPromptText("API key");
        TextField modelField = new TextField(settingsService.getAiModel().orElse(""));
        modelField.setPromptText("model name");
        Label statusLabel = new Label(aiStatusText());
        statusLabel.setWrapText(true);

        Button saveButton = new Button("Save AI Settings");
        saveButton.setOnAction(event -> {
            try {
                settingsService.saveAiSettings(
                    providerField.getText(),
                    baseUrlField.getText(),
                    apiKeyField.getText(),
                    modelField.getText()
                );
                reloadAiService();
                statusLabel.setText("Saved. " + aiStatusText());
            } catch (RuntimeException e) {
                statusLabel.setText(rootMessage(e));
            }
        });

        Button clearButton = new Button("Clear AI Settings");
        clearButton.setOnAction(event -> {
            settingsService.clearAiSettings();
            providerField.setText("openai-compatible");
            baseUrlField.clear();
            apiKeyField.clear();
            modelField.clear();
            reloadAiService();
            statusLabel.setText("Cleared. " + aiStatusText());
        });

        Button testButton = new Button("Test AI Explanation");
        testButton.setOnAction(event -> {
            WordCard sample = WordCard.createNew(currentDeck.getId(), "lucid", "清晰的; 易懂的");
            runBackground(
                () -> aiService.explain(sample),
                text -> statusLabel.setText((aiService.isAvailable() ? "Configured AI: " : "Mock AI: ") + text),
                error -> statusLabel.setText("AI test failed: " + rootMessage(error)),
                statusLabel,
                "Testing AI explanation..."
            );
        });

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(10);
        form.add(new Label("Provider"), 0, 0);
        form.add(providerField, 1, 0);
        form.add(new Label("Base URL"), 0, 1);
        form.add(baseUrlField, 1, 1);
        form.add(new Label("API key"), 0, 2);
        form.add(apiKeyField, 1, 2);
        form.add(new Label("Model"), 0, 3);
        form.add(modelField, 1, 3);
        GridPane.setHgrow(providerField, Priority.ALWAYS);
        GridPane.setHgrow(baseUrlField, Priority.ALWAYS);
        GridPane.setHgrow(apiKeyField, Priority.ALWAYS);
        GridPane.setHgrow(modelField, Priority.ALWAYS);
        HBox buttons = new HBox(10, saveButton, clearButton, testButton);
        return new VBox(10, sectionTitle("AI Explanation Provider"), form, buttons, statusLabel);
    }

    private void reloadDictionaryService() {
        dictionaryService = DictionaryServiceFactory.create(dictionaryCacheRepository, settingsService);
        updateHeaderSubtitle();
    }

    private void reloadAiService() {
        aiService = AiServiceFactory.create(aiCacheRepository, settingsService);
        updateHeaderSubtitle();
    }

    private String aiStatusText() {
        if (aiService.isAvailable()) {
            return "AI provider configured. Review explanations use HTTP provider with local cache.";
        }
        return "AI provider not configured. Mock explanation is used offline.";
    }

    private VBox createImportBox() {
        TextField importPathField = new TextField();
        importPathField.setPromptText("Choose legacy txt or GRE CSV");
        Button chooseButton = new Button("Choose file");
        chooseButton.setOnAction(event -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Choose import file");
            chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Import Files", "*.txt", "*.csv"),
                new FileChooser.ExtensionFilter("All Files", "*.*")
            );
            java.io.File file = chooser.showOpenDialog(chooseButton.getScene().getWindow());
            if (file != null) {
                importPathField.setText(file.toPath().toString());
            }
        });
        Button importLegacyButton = new Button("Import legacy txt");
        Button importCsvButton = new Button("Import GRE CSV");
        Button previewCsvButton = new Button("Preview GRE CSV");
        Button importStarterButton = new Button("Import GRE starter deck");
        Label importStatus = new Label();
        importStatus.setWrapText(true);

        importLegacyButton.setOnAction(event -> importFromPath(importPathField, importStatus, true));
        importCsvButton.setOnAction(event -> importFromPath(importPathField, importStatus, false));
        previewCsvButton.setOnAction(event -> previewGreCsv(importPathField, importStatus));
        importStarterButton.setOnAction(event -> {
            long deckId = currentDeck.getId();
            importStarterButton.setDisable(true);
            runBackground(
                () -> importExportService.importBundledGreStarter(deckId),
                result -> {
                    importStarterButton.setDisable(false);
                    afterImport(result, importStatus);
                },
                error -> {
                    importStarterButton.setDisable(false);
                    showError("Import failed", rootMessage(error));
                },
                importStatus,
                "Importing GRE starter deck..."
            );
        });

        HBox controls = new HBox(10, importPathField, chooseButton, importLegacyButton, previewCsvButton,
            importCsvButton, importStarterButton);
        controls.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(importPathField, Priority.ALWAYS);
        return new VBox(10, sectionTitle("Import"), controls, importStatus);
    }

    private Tab createStatisticsTab() {
        CategoryAxis reviewDateAxis = new CategoryAxis();
        reviewDateAxis.setTickLabelRotation(-35);
        reviewCountChart = new BarChart<>(reviewDateAxis, new NumberAxis());
        reviewCountChart.setTitle("Daily review count");
        reviewCountChart.setLegendVisible(false);
        reviewCountChart.setAnimated(false);
        reviewCountChart.setPrefHeight(280);
        reviewCountChart.setMinHeight(280);

        CategoryAxis accuracyDateAxis = new CategoryAxis();
        accuracyDateAxis.setTickLabelRotation(-35);
        accuracyChart = new LineChart<>(accuracyDateAxis, new NumberAxis(0, 1, 0.25));
        accuracyChart.setTitle("Accuracy trend");
        accuracyChart.setLegendVisible(false);
        accuracyChart.setAnimated(false);
        accuracyChart.setPrefHeight(280);
        accuracyChart.setMinHeight(280);

        memoryChart = new PieChart();
        memoryChart.setTitle("Memory strength distribution");
        memoryChart.setPrefHeight(260);
        memoryChart.setLabelsVisible(false);

        overdueStatsLabel = new Label("-");
        overdueStatsLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: 600;");
        hardestWordsArea = new TextArea();
        hardestWordsArea.setEditable(false);
        hardestWordsArea.setWrapText(true);
        hardestWordsArea.setPrefRowCount(8);
        analyticsArea = new TextArea();
        analyticsArea.setEditable(false);
        analyticsArea.setWrapText(true);
        analyticsArea.setPrefRowCount(8);

        Button refreshButton = new Button("Refresh statistics");
        refreshButton.setOnAction(event -> refreshStatistics());
        Button exportButton = new Button("Export Markdown report");
        exportButton.setOnAction(event -> exportReport());
        Button exportWordsButton = new Button("Export words CSV");
        exportWordsButton.setOnAction(event -> exportWordsCsv());
        Button exportLogsButton = new Button("Export review logs CSV");
        exportLogsButton.setOnAction(event -> exportReviewLogsCsv());
        Button exportBackupButton = new Button("Export JSON backup");
        exportBackupButton.setOnAction(event -> exportJsonBackup());
        Button importBackupButton = new Button("Import JSON backup");
        importBackupButton.setOnAction(event -> importJsonBackup());
        Button openDataDirButton = new Button("Open data folder");
        openDataDirButton.setOnAction(event -> openDataFolder());

        HBox buttons = new HBox(10, refreshButton, exportButton, exportWordsButton, exportLogsButton,
            exportBackupButton, importBackupButton, openDataDirButton);
        VBox charts = new VBox(16, reviewCountChart, accuracyChart, memoryChart, overdueStatsLabel,
            sectionTitle("Hardest Words"), hardestWordsArea,
            sectionTitle("Portfolio Summary"), analyticsArea, buttons);
        charts.setPadding(new Insets(24));
        ScrollPane scrollPane = new ScrollPane(charts);
        scrollPane.setFitToWidth(true);
        Tab tab = new Tab("Statistics", scrollPane);
        tab.setClosable(false);
        tab.setOnSelectionChanged(event -> {
            if (tab.isSelected()) {
                statisticsSelected = true;
                refreshStatistics();
            }
        });
        return tab;
    }

    private Tab createWordListTab() {
        searchField = new TextField();
        searchField.setPromptText("Search English, Chinese or tags");
        wordStatusFilter = new ComboBox<>();
        wordStatusFilter.getItems().setAll("All", "Due", "Weak", "Mastered", "Unverified");
        wordStatusFilter.getSelectionModel().select("All");
        tagFilterField = new TextField();
        tagFilterField.setPromptText("Tag");
        tagFilterField.setPrefWidth(120);
        posFilterField = new TextField();
        posFilterField.setPromptText("POS");
        posFilterField.setPrefWidth(120);
        PauseTransition searchDebounce = new PauseTransition(Duration.millis(250));
        searchDebounce.setOnFinished(event -> refreshWordTable());
        searchField.textProperty().addListener((observable, oldValue, newValue) -> searchDebounce.playFromStart());
        tagFilterField.textProperty().addListener((observable, oldValue, newValue) -> searchDebounce.playFromStart());
        posFilterField.textProperty().addListener((observable, oldValue, newValue) -> searchDebounce.playFromStart());
        wordStatusFilter.valueProperty().addListener((observable, oldValue, newValue) -> refreshWordTable());
        Button refreshButton = new Button("Refresh");
        refreshButton.setOnAction(event -> refreshWordTable());
        Button editButton = new Button("Edit selected");
        editButton.setOnAction(event -> editSelectedWord());
        Button deleteButton = new Button("Delete selected");
        deleteButton.setOnAction(event -> deleteSelectedWord());

        HBox controls = new HBox(10, searchField, wordStatusFilter, tagFilterField, posFilterField,
            refreshButton, editButton, deleteButton);
        HBox.setHgrow(searchField, Priority.ALWAYS);

        wordTable = new TableView<>(wordItems);
        wordTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        TableColumn<WordCard, String> englishCol = new TableColumn<>("English");
        englishCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getEnglish()));
        TableColumn<WordCard, String> chineseCol = new TableColumn<>("Chinese");
        chineseCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getChinese()));
        TableColumn<WordCard, String> nextCol = new TableColumn<>("Next review");
        nextCol.setCellValueFactory(data -> new SimpleStringProperty(DateTimeUtil.toDisplay(data.getValue().getNextReviewAt())));
        TableColumn<WordCard, String> intervalCol = new TableColumn<>("Interval");
        intervalCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getIntervalDays() + " days"));
        TableColumn<WordCard, String> strengthCol = new TableColumn<>("Memory");
        strengthCol.setCellValueFactory(data -> new SimpleStringProperty(formatPercent(data.getValue().calculateMemoryStrength(LocalDateTime.now()))));
        TableColumn<WordCard, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(data -> new SimpleStringProperty(statusText(data.getValue())));
        wordTable.getColumns().addAll(englishCol, chineseCol, nextCol, intervalCol, strengthCol, statusCol);

        VBox content = new VBox(12, controls, wordTable);
        content.setPadding(new Insets(24));
        VBox.setVgrow(wordTable, Priority.ALWAYS);
        Tab tab = new Tab("Word List", content);
        tab.setClosable(false);
        return tab;
    }

    private Label sectionTitle(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 16px; -fx-font-weight: 600;");
        return label;
    }

    private void addWordFromForm(TextField englishField, TextField chineseField, TextField phoneticField,
                                 TextField posField, TextArea exampleArea, TextArea noteArea,
                                 TextField tagsField, Label statusLabel) {
        try {
            Deck targetDeck = addDeckSelector == null ? currentDeck : addDeckSelector.getValue();
            if (targetDeck == null) {
                statusLabel.setText("Please select a target deck first.");
                return;
            }
            ValidatedWord validated = validationService.validate(
                englishField.getText(),
                chineseField.getText(),
                phoneticField.getText(),
                posField.getText(),
                exampleArea.getText(),
                noteArea.getText(),
                tagsField.getText()
            );
            if (wordRepository.findByEnglish(targetDeck.getId(), validated.english()).isPresent()) {
                statusLabel.setText("Word already exists in " + targetDeck.getName() + ": "
                    + validated.english() + ". Edit it in Word List.");
                return;
            }
            WordVerificationResult verification = dictionaryService.verify(validated.english());
            ValidatedWord wordToSave = validated;
            if (!verification.found()) {
                if (!confirmUnverifiedAdd(validated.english(), verification.message())) {
                    statusLabel.setText("Canceled: " + validated.english());
                    return;
                }
                wordToSave = new ValidatedWord(
                    validated.english(),
                    validated.chinese(),
                    validated.phonetic(),
                    validated.partOfSpeech(),
                    validated.exampleSentence(),
                    validated.note(),
                    appendTag(validated.tags(), "UNVERIFIED")
                );
            } else {
                wordToSave = new ValidatedWord(
                    validated.english(),
                    validated.chinese(),
                    validated.phonetic(),
                    validated.partOfSpeech(),
                    validated.exampleSentence(),
                    validated.note(),
                    appendTag(appendTag(validated.tags(), "VERIFIED"), verification.source())
                );
            }
            WordCard word = WordCard.createNew(targetDeck.getId(), wordToSave.english(), wordToSave.chinese());
            applyValidatedFields(word, wordToSave);
            wordRepository.save(word);
            GoalUpdate update = goalService.recordNewWords(targetDeck.getId(), 1);
            List<Achievement> unlocked = achievementService.evaluate(targetDeck.getId(), update.progress(), false, update.dailyGoalCompleted());
            englishField.clear();
            chineseField.clear();
            phoneticField.clear();
            posField.clear();
            exampleArea.clear();
            noteArea.clear();
            tagsField.clear();
            statusLabel.setText("Added to " + targetDeck.getName() + ": " + wordToSave.english()
                + (verification.found() ? " | Verified by " + verification.source() : " | Marked UNVERIFIED")
                + achievementText(unlocked));
            refreshAll();
        } catch (IllegalArgumentException e) {
            statusLabel.setText(e.getMessage());
        } catch (SQLException e) {
            showError("Add failed", e.getMessage());
        }
    }

    private void importFromPath(TextField importPathField, Label importStatus, boolean legacy) {
        if (importPathField.getText().isBlank()) {
            importStatus.setText("Please choose an import file first.");
            return;
        }
        try {
            Path path = Path.of(importPathField.getText().trim());
            long deckId = currentDeck.getId();
            runBackground(
                () -> legacy
                    ? importExportService.importLegacyTxt(path, deckId)
                    : importExportService.importGreCsv(path, deckId),
                result -> afterImport(result, importStatus),
                error -> showError("Import failed", rootMessage(error)),
                importStatus,
                "Importing..."
            );
        } catch (Exception e) {
            showError("Import failed", e.getMessage());
        }
    }

    private void previewGreCsv(TextField importPathField, Label importStatus) {
        if (importPathField.getText().isBlank()) {
            importStatus.setText("Please choose a GRE CSV file first.");
            return;
        }
        try {
            Path path = Path.of(importPathField.getText().trim());
            long deckId = currentDeck.getId();
            runBackground(
                () -> importExportService.previewGreCsv(path, deckId),
                preview -> importStatus.setText("Deck: " + currentDeck.getName()
                    + System.lineSeparator() + preview.toSummary()),
                error -> showError("Preview failed", rootMessage(error)),
                importStatus,
                "Analyzing CSV..."
            );
        } catch (Exception e) {
            showError("Preview failed", e.getMessage());
        }
    }

    private void afterImport(ImportResult result, Label importStatus) {
        GoalUpdate update = goalService.recordNewWords(currentDeck.getId(), result.importedCount());
        List<Achievement> unlocked = achievementService.evaluate(currentDeck.getId(), update.progress(), false, update.dailyGoalCompleted());
        importStatus.setText("Deck: " + currentDeck.getName() + System.lineSeparator()
            + result.toSummary() + achievementText(unlocked));
        refreshAll();
        loadNextReviewWord();
    }

    private void startReviewSession() {
        try {
            int target = selectedSessionTarget();
            reviewService.startSession(currentDeck.getId(), currentReviewMode(), target);
            loadNextReviewWord();
        } catch (IllegalArgumentException e) {
            showError("Start session failed", e.getMessage());
        }
    }

    private int selectedSessionTarget() {
        String value = sessionSizeSelector == null ? "20" : sessionSizeSelector.getValue();
        if ("All Due".equals(value)) {
            return 0;
        }
        if ("Custom".equals(value)) {
            String text = customSessionSizeField == null ? "" : customSessionSizeField.getText().trim();
            int custom = Integer.parseInt(text);
            if (custom <= 0 || custom > 500) {
                throw new IllegalArgumentException("Custom session size must be between 1 and 500.");
            }
            return custom;
        }
        return Integer.parseInt(value == null ? "20" : value);
    }

    private void submitCurrentAnswer() {
        if (currentReviewWord == null || submitAnswerButton.isDisabled()) {
            return;
        }
        ReviewAnswer answer = reviewService.submitAnswer(currentReviewWord.getId(), answerField.getText(), answerMode());
        WordCard wordForAi = currentReviewWord;
        String baseResult = "Correct answer: " + answer.correctAnswer()
            + System.lineSeparator() + "Your answer: " + answer.userAnswer()
            + System.lineSeparator() + "Answer similarity: " + formatPercent(answer.similarity());
        reviewResultArea.setText(baseResult + System.lineSeparator() + System.lineSeparator() + "AI explanation: loading...");
        runBackground(
            () -> aiService.explain(wordForAi),
            explanation -> {
                if (currentReviewWord != null && currentReviewWord.getId() == wordForAi.getId()) {
                    reviewResultArea.setText(baseResult + System.lineSeparator() + System.lineSeparator() + explanation);
                }
            },
            error -> reviewResultArea.setText(baseResult + System.lineSeparator() + System.lineSeparator()
                + "AI explanation unavailable: " + rootMessage(error)),
            null,
            null
        );
        ratingButtons.setDisable(false);
        submitAnswerButton.setDisable(true);
        answerField.setDisable(true);
    }

    private void rateCurrentWord(ReviewRating rating) {
        if (currentReviewWord == null) {
            return;
        }
        ReviewOutcome outcome = reviewService.rateCurrent(currentReviewWord.getId(), rating);
        refreshAll();
        loadNextReviewWord();
        if (currentReviewWord != null) {
            reviewResultArea.setText("Saved. XP +" + outcome.xpEarned() + achievementText(outcome.unlockedAchievements()));
        }
    }

    private void loadNextReviewWord() {
        Optional<WordCard> next = reviewService.nextWord(currentDeck.getId(), currentReviewMode());
        currentReviewWord = next.orElse(null);
        answerField.clear();
        reviewResultArea.clear();
        if (completionCard != null) {
            completionCard.setVisible(false);
            completionCard.setManaged(false);
        }
        ratingButtons.setDisable(true);
        submitAnswerButton.setDisable(currentReviewWord == null);
        answerField.setDisable(currentReviewWord == null);
        answerField.setPromptText(answerMode().getPrompt());
        updateSessionProgress();
        if (currentReviewWord == null) {
            showReviewCompletion();
        } else {
            reviewWordLabel.setText(answerMode() == ReviewMode.ZH_TO_EN
                ? currentReviewWord.getChinese()
                : currentReviewWord.getEnglish());
            reviewMetaLabel.setText(answerMode().getLabel()
                + " | Streak " + currentReviewWord.getConsecutiveCorrect()
                + " | Interval " + currentReviewWord.getIntervalDays()
                + " days | EF " + String.format("%.2f", currentReviewWord.getEasinessFactor())
                + " | Lapses " + currentReviewWord.getLapses());
            answerField.requestFocus();
        }
    }

    private void showReviewCompletion() {
        ReviewSessionSummary session = reviewService.sessionSummary();
        DailyGoalProgress progress = goalService.getTodayProgress(currentDeck.getId());
        reviewWordLabel.setText("Review complete");
        boolean targetReached = session.sessionGoal() > 0 && session.reviewedCount() >= session.sessionGoal();
        reviewMetaLabel.setText(targetReached ? "Session target reached." : "No due words right now.");
        completionTitleLabel.setText("Session Complete");
        completionMetricsLabel.setText("Completed: " + session.reviewedCount()
            + (session.sessionGoal() > 0 ? "/" + session.sessionGoal() : " / All Due")
            + " | Accuracy: " + formatPercent(session.accuracy())
            + " | XP: " + session.xpEarned()
            + System.lineSeparator() + "Today review goal: " + progress.reviewedCount() + "/" + progress.reviewGoal()
            + " | New words: " + progress.newWordsCount() + "/" + progress.newWordGoal()
            + System.lineSeparator() + "Unlocked: " + achievementNames(session.unlockedAchievements()));
        completionCard.setVisible(true);
        completionCard.setManaged(true);
        reviewResultArea.setText("Use Weak Words mode to keep working on your most fragile cards, or switch deck from the header.");
    }

    private ReviewMode currentReviewMode() {
        if (reviewModeSelector == null || reviewModeSelector.getValue() == null) {
            return ReviewMode.EN_TO_ZH;
        }
        return reviewModeSelector.getValue();
    }

    private ReviewMode answerMode() {
        return reviewService.currentQuestionMode();
    }

    private void updateSessionProgress() {
        if (sessionProgressLabel == null) {
            return;
        }
        ReviewSessionSummary session = reviewService.sessionSummary();
        String target = session.sessionGoal() > 0 ? String.valueOf(session.sessionGoal()) : "All Due";
        sessionProgressLabel.setText("Session " + session.reviewedCount() + "/" + target
            + " | Accuracy " + formatPercent(session.accuracy())
            + " | XP " + session.xpEarned());
    }

    private void refreshAll() {
        refreshDashboard();
        refreshDeckTable();
        refreshWordTable();
        if (statisticsSelected) {
            refreshStatistics();
        }
    }

    private void refreshDashboard() {
        DashboardStats stats = statsService.dashboardStats(currentDeck.getId());
        DailyGoalProgress progress = goalService.getTodayProgress(currentDeck.getId());
        List<Achievement> achievements = achievementService.getUnlockedAchievements(currentDeck.getId());
        totalWordsLabel.setText(String.valueOf(stats.totalWords()));
        dueTodayLabel.setText(String.valueOf(stats.dueToday()));
        reviewedTodayLabel.setText(progress.reviewedCount() + " / " + progress.reviewGoal());
        newWordsTodayLabel.setText(progress.newWordsCount() + " / " + progress.newWordGoal());
        accuracyTodayLabel.setText(formatPercent(progress.accuracy()));
        masteredWordsLabel.setText(String.valueOf(stats.masteredWords()));
        streakLabel.setText(progress.currentStreak() + " days");
        xpLabel.setText(String.valueOf(progress.totalXp()));
        reviewProgress.setProgress(progress.reviewProgress());
        newWordProgress.setProgress(progress.newWordProgress());
        badgesLabel.setText(achievementNames(achievements));
        databasePathLabel.setText("SQLite: " + databasePath.toAbsolutePath());
    }

    private void refreshDeckTable() {
        if (deckTable == null && archivedDeckTable == null) {
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            deckRows.setAll(deckRowsFor(deckService.activeDecks(), now));
            archivedDeckRows.setAll(deckRowsFor(deckService.archivedDecks(), now));
        } catch (RuntimeException e) {
            showError("Refresh decks failed", rootMessage(e));
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

    private void refreshWordTable() {
        if (wordTable == null) {
            return;
        }
        try {
            List<WordCard> words = wordRepository.search(currentDeck.getId(), searchField == null ? "" : searchField.getText());
            wordItems.setAll(words.stream().filter(this::matchesWordFilters).toList());
        } catch (SQLException e) {
            showError("Refresh failed", e.getMessage());
        }
    }

    private boolean matchesWordFilters(WordCard word) {
        String status = wordStatusFilter == null ? "All" : wordStatusFilter.getValue();
        if ("Due".equals(status) && !word.isDue(LocalDateTime.now())) {
            return false;
        }
        if ("Weak".equals(status) && !isWeak(word)) {
            return false;
        }
        if ("Mastered".equals(status) && !word.isMastered()) {
            return false;
        }
        if ("Unverified".equals(status) && !containsIgnoreCase(word.getTags(), "UNVERIFIED")) {
            return false;
        }
        String tag = tagFilterField == null ? "" : tagFilterField.getText();
        if (tag != null && !tag.isBlank() && !containsIgnoreCase(word.getTags(), tag.trim())) {
            return false;
        }
        String pos = posFilterField == null ? "" : posFilterField.getText();
        return pos == null || pos.isBlank() || containsIgnoreCase(word.getPartOfSpeech(), pos.trim());
    }

    private boolean isWeak(WordCard word) {
        return word.getLapses() > 0 || word.getConsecutiveCorrect() < 3 || word.getIntervalDays() <= 3;
    }

    private boolean containsIgnoreCase(String value, String needle) {
        return value != null && needle != null
            && value.toLowerCase(java.util.Locale.ROOT).contains(needle.toLowerCase(java.util.Locale.ROOT));
    }

    private void refreshStatistics() {
        if (reviewCountChart == null) {
            return;
        }
        List<DailyReviewStat> dailyStats = statsService.dailyReviewStats(currentDeck.getId(), 7);
        List<String> dayCategories = dailyStats.stream()
            .map(stat -> stat.date().getMonthValue() + "/" + stat.date().getDayOfMonth())
            .toList();
        ((CategoryAxis) reviewCountChart.getXAxis()).setCategories(FXCollections.observableArrayList(dayCategories));
        ((CategoryAxis) accuracyChart.getXAxis()).setCategories(FXCollections.observableArrayList(dayCategories));
        XYChart.Series<String, Number> reviewSeries = new XYChart.Series<>();
        XYChart.Series<String, Number> accuracySeries = new XYChart.Series<>();
        for (int i = 0; i < dailyStats.size(); i++) {
            DailyReviewStat stat = dailyStats.get(i);
            String day = dayCategories.get(i);
            reviewSeries.getData().add(new XYChart.Data<>(day, stat.reviewCount()));
            accuracySeries.getData().add(new XYChart.Data<>(day, stat.accuracy()));
        }
        reviewCountChart.getData().setAll(reviewSeries);
        accuracyChart.getData().setAll(accuracySeries);

        List<PieChart.Data> memoryData = statsService.memoryDistribution(currentDeck.getId()).stream()
            .filter(stat -> stat.count() > 0)
            .map(stat -> new PieChart.Data(stat.label(), stat.count()))
            .toList();
        memoryChart.getData().setAll(memoryData);
        overdueStatsLabel.setText("Overdue or due words: " + statsService.overdueCount(currentDeck.getId()));

        String hardest = statsService.hardestWords(currentDeck.getId(), 8).stream()
            .map(word -> word.english() + " | avg similarity " + formatPercent(word.averageSimilarity())
                + " | Again " + word.againCount())
            .collect(Collectors.joining(System.lineSeparator()));
        hardestWordsArea.setText(hardest.isBlank() ? "No review logs yet." : hardest);
        analyticsArea.setText("Spaced repetition: intervals grow when recall is strong and shrink after weak recall."
            + System.lineSeparator() + "Retrieval practice: every review stores the typed answer and response time."
            + System.lineSeparator() + "Adaptive scheduling: low similarity increases lapse pressure and future urgency."
            + System.lineSeparator() + "Learning analytics: charts summarize volume, accuracy, memory strength and hard words.");
    }

    private void exportReport() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export learning report");
        chooser.setInitialFileName("vocaboost-learning-report.md");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Markdown", "*.md"));
        java.io.File file = chooser.showSaveDialog(reviewCountChart.getScene().getWindow());
        if (file == null) {
            return;
        }
        try {
            Path exported = statsService.exportMarkdownReport(
                currentDeck.getId(),
                currentDeck.getName(),
                goalService.getTodayProgress(currentDeck.getId()),
                file.toPath()
            );
            showInfo("Report exported: " + exported.toAbsolutePath());
        } catch (Exception e) {
            showError("Export failed", e.getMessage());
        }
    }

    private void exportWordsCsv() {
        exportBackupFile("Export words CSV", "vocaboost-words.csv", "CSV", "*.csv",
            path -> backupService.exportWordsCsv(currentDeck.getId(), path));
    }

    private void exportReviewLogsCsv() {
        exportBackupFile("Export review logs CSV", "vocaboost-review-logs.csv", "CSV", "*.csv",
            path -> backupService.exportReviewLogsCsv(currentDeck.getId(), path));
    }

    private void exportJsonBackup() {
        exportBackupFile("Export JSON backup", "vocaboost-backup.json", "JSON", "*.json",
            path -> backupService.exportJsonBackup(currentDeck.getId(), path));
    }

    private void exportBackupFile(String title, String fileName, String extensionName, String extension,
                                  java.util.function.Function<Path, Path> exporter) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.setInitialFileName(fileName);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(extensionName, extension));
        java.io.File file = chooser.showSaveDialog(reviewCountChart.getScene().getWindow());
        if (file == null) {
            return;
        }
        Path output = file.toPath();
        runBackground(
            () -> exporter.apply(output),
            exported -> showInfo("Exported: " + exported.toAbsolutePath()),
            error -> showError("Export failed", rootMessage(error)),
            overdueStatsLabel,
            "Exporting..."
        );
    }

    private void importJsonBackup() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import JSON backup");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON", "*.json"));
        java.io.File file = chooser.showOpenDialog(reviewCountChart.getScene().getWindow());
        if (file == null) {
            return;
        }
        long deckId = currentDeck.getId();
        runBackground(
            () -> backupService.importJsonBackup(file.toPath(), deckId),
            result -> afterImport(result, overdueStatsLabel),
            error -> showError("Import failed", rootMessage(error)),
            overdueStatsLabel,
            "Importing backup..."
        );
    }

    private void openDataFolder() {
        Path parent = databasePath.toAbsolutePath().getParent();
        if (parent == null) {
            showInfo("Data folder is unavailable.");
            return;
        }
        try {
            if (!Desktop.isDesktopSupported()) {
                showInfo("Data folder: " + parent);
                return;
            }
            Desktop.getDesktop().open(parent.toFile());
        } catch (IOException e) {
            showError("Open folder failed", e.getMessage());
        }
    }

    private void editSelectedWord() {
        WordCard selected = wordTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showInfo("Please select a word to edit.");
            return;
        }

        TextField englishField = new TextField(selected.getEnglish());
        TextField chineseField = new TextField(selected.getChinese());
        TextField posField = new TextField(selected.getPartOfSpeech() == null ? "" : selected.getPartOfSpeech());
        TextField tagsField = new TextField(selected.getTags() == null ? "" : selected.getTags());
        TextArea exampleArea = new TextArea(selected.getExampleSentence() == null ? "" : selected.getExampleSentence());
        TextArea noteArea = new TextArea(selected.getNote() == null ? "" : selected.getNote());
        exampleArea.setPrefRowCount(3);
        noteArea.setPrefRowCount(3);

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(10);
        form.add(new Label("English"), 0, 0);
        form.add(englishField, 1, 0);
        form.add(new Label("Chinese"), 0, 1);
        form.add(chineseField, 1, 1);
        form.add(new Label("POS"), 0, 2);
        form.add(posField, 1, 2);
        form.add(new Label("Tags"), 0, 3);
        form.add(tagsField, 1, 3);
        form.add(new Label("Example"), 0, 4);
        form.add(exampleArea, 1, 4);
        form.add(new Label("Notes"), 0, 5);
        form.add(noteArea, 1, 5);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Edit word");
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            try {
                ValidatedWord validated = validationService.validate(
                    englishField.getText(),
                    chineseField.getText(),
                    selected.getPhonetic(),
                    posField.getText(),
                    exampleArea.getText(),
                    noteArea.getText(),
                    tagsField.getText()
                );
                Optional<WordCard> duplicate = wordRepository.findByEnglish(currentDeck.getId(), validated.english());
                if (duplicate.isPresent() && duplicate.get().getId() != selected.getId()) {
                    showError("Save failed", "Another word already uses this English value.");
                    return;
                }
                selected.setEnglish(validated.english());
                selected.setChinese(validated.chinese());
                applyValidatedFields(selected, validated);
                wordRepository.save(selected);
                refreshAll();
            } catch (IllegalArgumentException | SQLException e) {
                showError("Save failed", e.getMessage());
            }
        }
    }

    private void deleteSelectedWord() {
        WordCard selected = wordTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showInfo("Please select a word to delete.");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Delete word");
        confirm.setHeaderText("Delete " + selected.getEnglish() + "?");
        confirm.setContentText("Related review logs will also be removed.");
        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            try {
                wordRepository.deleteById(selected.getId());
                refreshAll();
                loadNextReviewWord();
            } catch (SQLException e) {
                showError("Delete failed", e.getMessage());
            }
        }
    }

    private void applyValidatedFields(WordCard word, ValidatedWord validated) {
        word.setPhonetic(validated.phonetic());
        word.setPartOfSpeech(validated.partOfSpeech());
        word.setExampleSentence(validated.exampleSentence());
        word.setNote(validated.note());
        word.setTags(validated.tags());
    }

    private boolean confirmUnverifiedAdd(String english, String message) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("词条未找到");
        confirm.setHeaderText("词条未找到：" + english);
        confirm.setContentText((message == null || message.isBlank() ? "本地和在线词典都未验证该词条。" : message)
            + System.lineSeparator() + "是否强制添加并标记为 UNVERIFIED？");
        Optional<ButtonType> result = confirm.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
    }

    private String appendTag(String tags, String tag) {
        String clean = tags == null ? "" : tags.trim();
        if (tag == null || tag.isBlank()) {
            return clean;
        }
        if (clean.toLowerCase().contains(tag.toLowerCase())) {
            return clean;
        }
        return clean.isBlank() ? tag : clean + "; " + tag;
    }

    private String dictionaryNote(DictionaryEntry entry) {
        StringBuilder builder = new StringBuilder();
        if (entry.definition() != null && !entry.definition().isBlank()) {
            builder.append("English definition: ").append(entry.definition().trim());
        }
        if (entry.source() != null && !entry.source().isBlank()) {
            if (builder.length() > 0) {
                builder.append(System.lineSeparator());
            }
            builder.append("Dictionary source: ").append(entry.source().trim());
        }
        return builder.toString();
    }

    private String statusText(WordCard word) {
        if (word.isMastered()) {
            return "Mastered";
        }
        if (word.isDue(LocalDateTime.now())) {
            return "Due";
        }
        if (word.getRepetitions() == 0) {
            return "New";
        }
        return "Learning";
    }

    private String achievementText(List<Achievement> achievements) {
        if (achievements == null || achievements.isEmpty()) {
            return "";
        }
        return System.lineSeparator() + "Unlocked: " + achievementNames(achievements);
    }

    private String achievementNames(List<Achievement> achievements) {
        if (achievements == null || achievements.isEmpty()) {
            return "None yet";
        }
        return achievements.stream().map(Achievement::name).collect(Collectors.joining(", "));
    }

    private String formatPercent(double value) {
        return String.format("%.0f%%", value * 100);
    }

    private <T> void runBackground(Callable<T> callable, Consumer<T> onSuccess, Consumer<Throwable> onFailure,
                                   Label statusLabel, String runningMessage) {
        if (statusLabel != null && runningMessage != null) {
            statusLabel.setText(runningMessage);
        }
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return callable.call();
            }
        };
        task.setOnSucceeded(event -> onSuccess.accept(task.getValue()));
        task.setOnFailed(event -> onFailure.accept(task.getException()));
        Thread thread = new Thread(task, "vocaboost-background-task");
        thread.setDaemon(true);
        thread.start();
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message == null ? "Unknown error" : message);
        alert.showAndWait();
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? throwable.getMessage() : current.getMessage();
    }

    private void showInfo(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Info");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}
