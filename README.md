# VocaBoost Desktop

VocaBoost is a Java 17 + JavaFX + SQLite desktop vocabulary trainer rebuilt from an older Java CLI spaced-repetition project. It focuses on a complete local learning loop: add/import words, review with typed retrieval practice, measure answer similarity, schedule future reviews adaptively, and track learning progress.

## Highlights

- JavaFX desktop app with SQLite persistence.
- Spaced repetition scheduler based on SM-2 style intervals.
- Typed answer review with similarity percentage and Again / Hard / Good / Easy self-rating.
- Similarity-aware scheduling: vague or low-similarity answers reduce easiness and increase future urgency.
- Goals and achievements: daily review goal, daily new-word goal, session target, streak, XP, and badges.
- Statistics page with JavaFX charts for daily review volume, accuracy trend, memory strength, hard words, and overdue count.
- Portfolio-ready Markdown learning report export.
- Manual add validation plus configurable dictionary lookup with offline Mock fallback.
- Legacy txt import and GRE CSV starter-deck import.
- Empty database bootstrap: first launch imports the bundled GRE starter sample so Word List and Review are immediately testable.

## Screenshots

Add screenshots after packaging or demo recording:

```text
docs/screenshots/dashboard.png
docs/screenshots/review.png
docs/screenshots/statistics.png
```

## Tech Stack

- Java 17+
- JavaFX
- Maven
- SQLite
- JUnit 5

## Database Location

The app creates a local SQLite database automatically:

```text
%USERPROFILE%\.vocab-trainer\vocab.db
```

Existing databases are migrated with `CREATE TABLE IF NOT EXISTS`; the app does not delete your saved words.

## Run in IntelliJ IDEA

Open `D:\java\VocaBoost` in IntelliJ IDEA, then run:

```powershell
mvn javafx:run
```

If `mvn` is not available in PATH but IntelliJ is installed:

```powershell
& 'C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.1.1\plugins\maven\lib\maven3\bin\mvn.cmd' javafx:run
```

Run tests:

```powershell
mvn test
```

## Dictionary Lookup

Lookup uses this order:

1. Local dictionary: bundled GRE starter sample, plus optional `ECDICT_CSV_PATH`.
2. Configured API from `DICTIONARY_API_BASE_URL`.
3. Public online dictionaries: dictionaryapi.dev, then Wiktionary.
4. Mock dictionary for known sample words.

To add an ECDICT-compatible local CSV, set:

```powershell
$env:ECDICT_CSV_PATH = 'D:\path\to\ecdict.csv'
```

To use a private API, set environment variables before launching:

```powershell
$env:DICTIONARY_API_BASE_URL = 'https://your-api.example/lookup'
$env:DICTIONARY_API_KEY = 'your-key'
mvn javafx:run
```

The app sends `GET {baseUrl}?word={english}` and includes the key in `Authorization: Bearer ...` and `X-API-Key`. If local and online dictionaries cannot verify a word, the UI shows "词条未找到" and lets you cancel or force-add it. Force-added words are tagged `UNVERIFIED`.

Expected useful JSON fields include `english` or `word`, `chinese` or `translation` or `meaning` or `definition`, `pos` or `partOfSpeech`, `phonetic`, `example`, and `source`.

## Import Formats

Legacy txt format:

```text
english;chinese;addedDate;lastReviewed;easiness;interval;consecutiveCorrect
```

Date format:

```text
yyyy-MM-dd HH:mm:ss
```

GRE CSV format:

```csv
english,chinese,pos,example,tags
abate,"减弱; 减少",verb,"The storm began to abate.",gre;starter
```

The bundled GRE starter deck is a small legally maintainable sample at:

```text
src/main/resources/data/gre_starter_sample.csv
```

For a larger GRE list, prepare your own CSV in the format above and use `Add / Import -> Import GRE CSV`. Unknown or copyrighted 2000-word lists are intentionally not bundled.

GRE CSV import stops at 2000 imported words. The one-click GRE starter import refreshes Dashboard, Word List, and Review immediately after import.

## Build a Clickable Windows App

For a click-to-run Windows app folder with `VocaBoost.exe`, use:

```powershell
.\scripts\package-windows.ps1
```

Output:

```text
target\dist\VocaBoost\VocaBoost.exe
```

To build a Windows installer `.exe`, run:

```powershell
.\scripts\package-windows.ps1 -PackageType exe
```

Installer mode may require WiX Toolset on Windows. If WiX is missing, use the default `app-image` mode.

## Project Structure

```text
src/main/java/com/vocabtrainer
|- app          JavaFX application entry point
|- domain       WordCard, Deck, ReviewLog, goals, achievements, dictionary and stats records
|- repository   SQLite setup and CRUD
|- service      review scheduling, goals, achievements, validation, dictionary, import, stats, AI interface
|- ui           JavaFX screens
`- util         date and path utilities
```

Supporting folders:

```text
samples/       legacy import test data
scripts/       local build and packaging scripts
```

Business logic does not depend on `Scanner`, `System.out`, `System.exit()`, or JavaFX controls.

## Test Coverage

Current tests cover:

- `SimilarityService`
- `ReviewScheduler`
- `WordSelector`
- `WordValidationService`
- `GoalService`
- `AchievementService`
- `DictionaryService`
- `ImportExportService`
- SQLite repository CRUD and new persistence tables

Verified command:

```powershell
& 'C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.1.1\plugins\maven\lib\maven3\bin\mvn.cmd' '-Dmaven.repo.local=.m2\repository' test
```

Latest local result: 23 tests, 0 failures.
