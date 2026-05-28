# VocaBoost Desktop

VocaBoost is a JavaFX + SQLite desktop vocabulary trainer rebuilt from an older Java CLI spaced-repetition project. The current version focuses on a reliable core workflow: word CRUD, legacy txt import, answer similarity feedback, Again/Hard/Good/Easy self-rating, SQLite persistence, and spaced-repetition scheduling.

## Tech Stack

- Java 17+
- JavaFX
- Maven
- SQLite
- JUnit 5

## Why Maven Is Needed During Development

The app depends on external libraries such as JavaFX, SQLite JDBC, and JUnit. Maven reads `pom.xml`, downloads those dependencies, compiles the code, runs tests, and starts the JavaFX app. That is why development runs through commands such as `mvn javafx:run` instead of directly double-clicking a `.java` file.

For end users, Maven is not required after the app is packaged with `jpackage`; they can launch `VocaBoost.exe` directly.

## Features

- Dashboard: total words, due words, completed reviews, accuracy, mastered words, and daily progress.
- Review: show English prompt, collect Chinese answer, reveal correct answer and similarity, then self-rate with Again/Hard/Good/Easy.
- Add / Import: add words manually or import legacy semicolon-separated txt files.
- Word List: search, edit, delete, and inspect memory strength, interval, status, and next review date.
- Mock AI: deterministic local learning hint; no API key is required.

## Database Location

The app creates a local SQLite database automatically:

```text
%USERPROFILE%\.vocab-trainer\vocab.db
```

## Sample Import Files

The repository includes English-named sample files under `samples/`:

```text
samples/sample-gre-words.txt
samples/sample-legacy-import.txt
```

Both use the legacy import format:

```text
english;chinese;addedDate;lastReviewed;easiness;interval;consecutiveCorrect
```

Date format:

```text
yyyy-MM-dd HH:mm:ss
```

Bad rows and duplicate words are skipped with a summary in the UI. The original txt file is never modified.

## Run in IntelliJ IDEA

Open the project folder in IntelliJ IDEA, then run one of these:

```powershell
mvn javafx:run
```

If `mvn` is not available in PATH but IntelliJ is installed, use IntelliJ's bundled Maven:

```powershell
& 'C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.1.1\plugins\maven\lib\maven3\bin\mvn.cmd' javafx:run
```

Run tests:

```powershell
mvn test
```

## Build a Clickable Windows App

For a click-to-run Windows app folder with `VocaBoost.exe`, use:

```powershell
.\scripts\package-windows.ps1
```

Output:

```text
target\dist\VocaBoost\VocaBoost.exe
```

That `VocaBoost.exe` can be double-clicked. It includes a custom runtime image, so users do not need to install Maven. This is the best packaging option for quick demos.

To build a Windows installer `.exe`, run:

```powershell
.\scripts\package-windows.ps1 -PackageType exe
```

Installer mode may require WiX Toolset on Windows. If WiX is missing, use the default `app-image` mode above.

## Project Structure

```text
src/main/java/com/vocabtrainer
├─ app          JavaFX application entry point
├─ domain       WordCard, Deck, ReviewLog, ReviewRating
├─ repository   SQLite setup and CRUD
├─ service      review scheduling, similarity, import, stats, AI interface
├─ ui           JavaFX screens
└─ util         date and path utilities
```

Supporting folders:

```text
samples/       import test data
scripts/       local build and packaging scripts
```

Business logic no longer depends on `Scanner`, `System.out`, or `System.exit()`.

## Test Coverage

Current tests cover:

- `SimilarityService`
- `ReviewScheduler`
- `ImportExportService`
- SQLite repository CRUD and review logs

## Future Work

- Add complete Goals/Achievements UI and database support.
- Add real AI service, environment-variable configuration, and SQLite AI cache.
- Add CSV/JSON import and export.
- Add richer charts and portfolio screenshots.