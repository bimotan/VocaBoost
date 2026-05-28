package com.vocabtrainer.ui;

import com.vocabtrainer.domain.Deck;
import com.vocabtrainer.domain.ReviewRating;
import com.vocabtrainer.domain.WordCard;
import com.vocabtrainer.repository.WordRepository;
import com.vocabtrainer.service.AiService;
import com.vocabtrainer.service.DashboardStats;
import com.vocabtrainer.service.ImportExportService;
import com.vocabtrainer.service.ImportResult;
import com.vocabtrainer.service.ReviewAnswer;
import com.vocabtrainer.service.ReviewService;
import com.vocabtrainer.service.StatsService;
import com.vocabtrainer.util.DateTimeUtil;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public class MainWindow {
    private static final int DAILY_REVIEW_GOAL = 20;

    private final Deck deck;
    private final WordRepository wordRepository;
    private final ReviewService reviewService;
    private final ImportExportService importExportService;
    private final StatsService statsService;
    private final AiService aiService;
    private final Path databasePath;

    private final ObservableList<WordCard> wordItems = FXCollections.observableArrayList();
    private final Label totalWordsLabel = new Label("-");
    private final Label dueTodayLabel = new Label("-");
    private final Label reviewedTodayLabel = new Label("-");
    private final Label accuracyTodayLabel = new Label("-");
    private final Label masteredWordsLabel = new Label("-");
    private final Label databasePathLabel = new Label();
    private final ProgressBar dailyProgress = new ProgressBar(0);

    private TableView<WordCard> wordTable;
    private TextField searchField;
    private Label reviewWordLabel;
    private Label reviewMetaLabel;
    private TextField answerField;
    private TextArea reviewResultArea;
    private Button submitAnswerButton;
    private HBox ratingButtons;
    private WordCard currentReviewWord;

    public MainWindow(Deck deck, WordRepository wordRepository, ReviewService reviewService,
                      ImportExportService importExportService, StatsService statsService,
                      AiService aiService, Path databasePath) {
        this.deck = deck;
        this.wordRepository = wordRepository;
        this.reviewService = reviewService;
        this.importExportService = importExportService;
        this.statsService = statsService;
        this.aiService = aiService;
        this.databasePath = databasePath;
    }

    public Scene createScene() {
        BorderPane root = new BorderPane();
        root.setTop(createHeader());

        TabPane tabs = new TabPane();
        tabs.getTabs().add(createDashboardTab());
        tabs.getTabs().add(createReviewTab());
        tabs.getTabs().add(createAddImportTab());
        tabs.getTabs().add(createWordListTab());
        root.setCenter(tabs);

        refreshAll();
        return new Scene(root, 1080, 720);
    }

    private VBox createHeader() {
        Label title = new Label("Vocabulary Trainer");
        title.setStyle("-fx-font-size: 24px; -fx-font-weight: 700;");
        Label subtitle = new Label("当前词库：" + deck.getName() + " | AI：" + (aiService.isAvailable() ? "已启用" : "Mock/未配置"));
        subtitle.setStyle("-fx-text-fill: #4b5563;");
        VBox box = new VBox(4, title, subtitle);
        box.setPadding(new Insets(18, 24, 12, 24));
        box.setStyle("-fx-background-color: #f8fafc; -fx-border-color: #e5e7eb; -fx-border-width: 0 0 1 0;");
        return box;
    }

    private Tab createDashboardTab() {
        GridPane grid = new GridPane();
        grid.setPadding(new Insets(24));
        grid.setHgap(18);
        grid.setVgap(14);

        addStat(grid, 0, "总单词", totalWordsLabel);
        addStat(grid, 1, "今日待复习", dueTodayLabel);
        addStat(grid, 2, "今日已完成", reviewedTodayLabel);
        addStat(grid, 3, "今日正确率", accuracyTodayLabel);
        addStat(grid, 4, "已掌握", masteredWordsLabel);

        Label progressTitle = new Label("每日复习目标");
        progressTitle.setStyle("-fx-font-size: 16px; -fx-font-weight: 600;");
        dailyProgress.setPrefWidth(360);
        databasePathLabel.setStyle("-fx-text-fill: #6b7280;");
        Button refreshButton = new Button("刷新");
        refreshButton.setOnAction(event -> refreshAll());

        VBox progressBox = new VBox(10, progressTitle, dailyProgress, databasePathLabel, refreshButton);
        progressBox.setPadding(new Insets(18, 0, 0, 0));
        grid.add(progressBox, 0, 5, 2, 1);

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

    private Tab createReviewTab() {
        reviewWordLabel = new Label("加载中...");
        reviewWordLabel.setStyle("-fx-font-size: 34px; -fx-font-weight: 700;");
        reviewMetaLabel = new Label();
        reviewMetaLabel.setStyle("-fx-text-fill: #4b5563;");
        answerField = new TextField();
        answerField.setPromptText("输入中文释义");
        answerField.setPrefWidth(420);
        submitAnswerButton = new Button("提交答案");
        submitAnswerButton.setOnAction(event -> submitCurrentAnswer());
        answerField.setOnAction(event -> submitCurrentAnswer());

        reviewResultArea = new TextArea();
        reviewResultArea.setEditable(false);
        reviewResultArea.setWrapText(true);
        reviewResultArea.setPrefRowCount(7);

        ratingButtons = new HBox(10,
            ratingButton(ReviewRating.AGAIN),
            ratingButton(ReviewRating.HARD),
            ratingButton(ReviewRating.GOOD),
            ratingButton(ReviewRating.EASY)
        );
        ratingButtons.setDisable(true);

        HBox answerBox = new HBox(10, answerField, submitAnswerButton);
        answerBox.setAlignment(Pos.CENTER_LEFT);
        VBox content = new VBox(16, reviewWordLabel, reviewMetaLabel, answerBox, reviewResultArea, ratingButtons);
        content.setPadding(new Insets(28));
        VBox.setVgrow(reviewResultArea, Priority.ALWAYS);

        Tab tab = new Tab("Review", content);
        tab.setClosable(false);
        return tab;
    }

    private Button ratingButton(ReviewRating rating) {
        Button button = new Button(rating.getLabel());
        button.setMinWidth(90);
        button.setOnAction(event -> rateCurrentWord(rating));
        return button;
    }

    private Tab createAddImportTab() {
        TextField englishField = new TextField();
        englishField.setPromptText("英文");
        TextField chineseField = new TextField();
        chineseField.setPromptText("中文释义");
        TextField phoneticField = new TextField();
        phoneticField.setPromptText("音标，可选");
        TextField tagsField = new TextField();
        tagsField.setPromptText("标签，可选");
        TextArea noteArea = new TextArea();
        noteArea.setPromptText("笔记，可选");
        noteArea.setPrefRowCount(4);
        Label addStatus = new Label();

        Button addButton = new Button("添加单词");
        addButton.setOnAction(event -> {
            String english = englishField.getText().trim();
            String chinese = chineseField.getText().trim();
            if (english.isEmpty() || chinese.isEmpty()) {
                addStatus.setText("英文和中文不能为空。");
                return;
            }
            try {
                if (wordRepository.findByEnglish(deck.getId(), english).isPresent()) {
                    addStatus.setText("单词已存在：" + english);
                    return;
                }
                WordCard word = WordCard.createNew(deck.getId(), english, chinese);
                word.setPhonetic(phoneticField.getText());
                word.setTags(tagsField.getText());
                word.setNote(noteArea.getText());
                wordRepository.save(word);
                englishField.clear();
                chineseField.clear();
                phoneticField.clear();
                tagsField.clear();
                noteArea.clear();
                addStatus.setText("已添加：" + english);
                refreshAll();
            } catch (SQLException e) {
                showError("添加失败", e.getMessage());
            }
        });

        GridPane addForm = new GridPane();
        addForm.setHgap(10);
        addForm.setVgap(10);
        addForm.add(new Label("英文"), 0, 0);
        addForm.add(englishField, 1, 0);
        addForm.add(new Label("中文"), 0, 1);
        addForm.add(chineseField, 1, 1);
        addForm.add(new Label("音标"), 0, 2);
        addForm.add(phoneticField, 1, 2);
        addForm.add(new Label("标签"), 0, 3);
        addForm.add(tagsField, 1, 3);
        addForm.add(new Label("笔记"), 0, 4);
        addForm.add(noteArea, 1, 4);
        addForm.add(addButton, 1, 5);
        addForm.add(addStatus, 1, 6);

        TextField importPathField = new TextField();
        importPathField.setPromptText("选择旧 txt 文件，例如 samples/sample-legacy-import.txt");
        Button chooseButton = new Button("选择文件");
        chooseButton.setOnAction(event -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("选择旧单词 txt 文件");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text Files", "*.txt"));
            java.io.File file = chooser.showOpenDialog(chooseButton.getScene().getWindow());
            if (file != null) {
                importPathField.setText(file.toPath().toString());
            }
        });
        Button importButton = new Button("导入旧词库");
        Label importStatus = new Label();
        importButton.setOnAction(event -> {
            if (importPathField.getText().isBlank()) {
                importStatus.setText("请先选择 txt 文件。");
                return;
            }
            try {
                ImportResult result = importExportService.importLegacyTxt(Path.of(importPathField.getText().trim()), deck.getId());
                importStatus.setText(result.toSummary());
                refreshAll();
            } catch (Exception e) {
                showError("导入失败", e.getMessage());
            }
        });
        HBox importControls = new HBox(10, importPathField, chooseButton, importButton);
        importControls.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(importPathField, Priority.ALWAYS);
        VBox importBox = new VBox(10, sectionTitle("旧 txt 导入"), importControls, importStatus);

        VBox content = new VBox(24, sectionTitle("添加单词"), addForm, importBox);
        content.setPadding(new Insets(24));
        Tab tab = new Tab("Add / Import", content);
        tab.setClosable(false);
        return tab;
    }

    private Tab createWordListTab() {
        searchField = new TextField();
        searchField.setPromptText("搜索英文、中文或标签");
        searchField.textProperty().addListener((observable, oldValue, newValue) -> refreshWordTable());
        Button refreshButton = new Button("刷新");
        refreshButton.setOnAction(event -> refreshWordTable());
        Button editButton = new Button("编辑选中");
        editButton.setOnAction(event -> editSelectedWord());
        Button deleteButton = new Button("删除选中");
        deleteButton.setOnAction(event -> deleteSelectedWord());

        HBox controls = new HBox(10, searchField, refreshButton, editButton, deleteButton);
        HBox.setHgrow(searchField, Priority.ALWAYS);

        wordTable = new TableView<>(wordItems);
        wordTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        TableColumn<WordCard, String> englishCol = new TableColumn<>("英文");
        englishCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getEnglish()));
        TableColumn<WordCard, String> chineseCol = new TableColumn<>("中文");
        chineseCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getChinese()));
        TableColumn<WordCard, String> nextCol = new TableColumn<>("下次复习");
        nextCol.setCellValueFactory(data -> new SimpleStringProperty(DateTimeUtil.toDisplay(data.getValue().getNextReviewAt())));
        TableColumn<WordCard, String> intervalCol = new TableColumn<>("间隔");
        intervalCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getIntervalDays() + " 天"));
        TableColumn<WordCard, String> strengthCol = new TableColumn<>("记忆强度");
        strengthCol.setCellValueFactory(data -> new SimpleStringProperty(formatPercent(data.getValue().calculateMemoryStrength(LocalDateTime.now()))));
        TableColumn<WordCard, String> statusCol = new TableColumn<>("状态");
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

    private void submitCurrentAnswer() {
        if (currentReviewWord == null || submitAnswerButton.isDisabled()) {
            return;
        }
        ReviewAnswer answer = reviewService.submitAnswer(currentReviewWord.getId(), answerField.getText());
        reviewResultArea.setText("正确答案：" + answer.correctAnswer()
            + System.lineSeparator() + "你的答案：" + answer.userAnswer()
            + System.lineSeparator() + "相似度：" + formatPercent(answer.similarity())
            + System.lineSeparator() + System.lineSeparator()
            + aiService.explain(currentReviewWord));
        ratingButtons.setDisable(false);
        submitAnswerButton.setDisable(true);
        answerField.setDisable(true);
    }

    private void rateCurrentWord(ReviewRating rating) {
        if (currentReviewWord == null) {
            return;
        }
        reviewService.rateCurrent(currentReviewWord.getId(), rating);
        loadNextReviewWord();
        refreshAll();
    }

    private void loadNextReviewWord() {
        Optional<WordCard> next = reviewService.nextDueWord(deck.getId());
        currentReviewWord = next.orElse(null);
        answerField.clear();
        reviewResultArea.clear();
        ratingButtons.setDisable(true);
        submitAnswerButton.setDisable(currentReviewWord == null);
        answerField.setDisable(currentReviewWord == null);
        if (currentReviewWord == null) {
            reviewWordLabel.setText("今天没有待复习单词");
            reviewMetaLabel.setText("可以添加新词或导入旧词库。");
        } else {
            reviewWordLabel.setText(currentReviewWord.getEnglish());
            reviewMetaLabel.setText("连续正确 " + currentReviewWord.getConsecutiveCorrect()
                + " 次 | 间隔 " + currentReviewWord.getIntervalDays()
                + " 天 | EF " + String.format("%.2f", currentReviewWord.getEasinessFactor()));
            answerField.requestFocus();
        }
    }

    private void refreshAll() {
        refreshDashboard();
        refreshWordTable();
        loadNextReviewWord();
    }

    private void refreshDashboard() {
        DashboardStats stats = statsService.dashboardStats(deck.getId());
        totalWordsLabel.setText(String.valueOf(stats.totalWords()));
        dueTodayLabel.setText(String.valueOf(stats.dueToday()));
        reviewedTodayLabel.setText(stats.reviewedToday() + " / " + DAILY_REVIEW_GOAL);
        accuracyTodayLabel.setText(formatPercent(stats.accuracyToday()));
        masteredWordsLabel.setText(String.valueOf(stats.masteredWords()));
        dailyProgress.setProgress(Math.min(1.0, stats.reviewedToday() / (double) DAILY_REVIEW_GOAL));
        databasePathLabel.setText("SQLite：" + databasePath.toAbsolutePath());
    }

    private void refreshWordTable() {
        if (wordTable == null) {
            return;
        }
        try {
            List<WordCard> words = wordRepository.search(deck.getId(), searchField == null ? "" : searchField.getText());
            wordItems.setAll(words);
        } catch (SQLException e) {
            showError("刷新列表失败", e.getMessage());
        }
    }

    private void editSelectedWord() {
        WordCard selected = wordTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showInfo("请选择要编辑的单词。");
            return;
        }

        TextField englishField = new TextField(selected.getEnglish());
        TextField chineseField = new TextField(selected.getChinese());
        TextField tagsField = new TextField(selected.getTags() == null ? "" : selected.getTags());
        TextArea noteArea = new TextArea(selected.getNote() == null ? "" : selected.getNote());
        noteArea.setPrefRowCount(4);

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(10);
        form.add(new Label("英文"), 0, 0);
        form.add(englishField, 1, 0);
        form.add(new Label("中文"), 0, 1);
        form.add(chineseField, 1, 1);
        form.add(new Label("标签"), 0, 2);
        form.add(tagsField, 1, 2);
        form.add(new Label("笔记"), 0, 3);
        form.add(noteArea, 1, 3);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("编辑单词");
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            try {
                selected.setEnglish(englishField.getText().trim());
                selected.setChinese(chineseField.getText().trim());
                selected.setTags(tagsField.getText());
                selected.setNote(noteArea.getText());
                wordRepository.save(selected);
                refreshAll();
            } catch (SQLException e) {
                showError("保存失败", e.getMessage());
            }
        }
    }

    private void deleteSelectedWord() {
        WordCard selected = wordTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showInfo("请选择要删除的单词。");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("删除单词");
        confirm.setHeaderText("确认删除 " + selected.getEnglish() + "？");
        confirm.setContentText("删除后相关复习记录也会被移除。");
        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            try {
                wordRepository.deleteById(selected.getId());
                refreshAll();
            } catch (SQLException e) {
                showError("删除失败", e.getMessage());
            }
        }
    }

    private String statusText(WordCard word) {
        if (word.isMastered()) {
            return "已掌握";
        }
        if (word.isDue(LocalDateTime.now())) {
            return "待复习";
        }
        if (word.getRepetitions() == 0) {
            return "新词";
        }
        return "学习中";
    }

    private String formatPercent(double value) {
        return String.format("%.0f%%", value * 100);
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message == null ? "未知错误" : message);
        alert.showAndWait();
    }

    private void showInfo(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("提示");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}