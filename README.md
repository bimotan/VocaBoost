# VocaBoost Desktop

VocaBoost is a Java 17 + JavaFX + SQLite desktop vocabulary trainer rebuilt from an older Java CLI spaced-repetition project. It focuses on a complete local learning loop: add/import words, review with typed retrieval practice, measure answer similarity, schedule future reviews adaptively, and track learning progress.

It is suitable as a learning analytics prototype because each review produces structured evidence: typed recall, similarity score, rating, response time, scheduling state, daily goals, streak, XP, and deck-scoped progress. The Statistics and Markdown report views turn those logs into portfolio-friendly artifacts for explaining spaced repetition, retrieval practice, adaptive scheduling, and learner modeling.

## Highlights

- JavaFX desktop app with SQLite persistence.
- Multi-deck management: create, switch, rename, archive, and restore decks.
- Spaced repetition scheduler based on SM-2 style intervals.
- Typed answer review with similarity percentage, Again / Hard / Good / Easy self-rating, English-to-Chinese, Chinese-to-English, mixed, and weak-word modes.
- Review session presets: 10 / 20 / 50 / All Due / Custom, with Start Session and Reset Session controls.
- Similarity-aware scheduling: vague or low-similarity answers reduce easiness and increase future urgency.
- Goals and achievements: daily review goal, daily new-word goal, session target, streak, XP, and badges.
- Statistics page with JavaFX charts for daily review volume, accuracy trend, memory strength, hard words, and overdue count.
- Portfolio-ready Markdown learning report export.
- Data safety exports: words CSV, review-log CSV, JSON backup, and JSON backup import.
- Manual add validation plus configurable dictionary lookup with ECDICT path setup, online lookup, cache, and offline Mock fallback.
- Optional OpenAI-compatible AI explanations with `ai_cache` and Mock fallback; no key is required for offline use.
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

Clean clone setup:

```powershell
git clone https://github.com/bimotan/VocaBoost.git
cd VocaBoost
mvn test
mvn javafx:run
```

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

1. Local dictionary: saved ECDICT CSV path from the Add / Import page.
2. Environment fallback `ECDICT_CSV_PATH`.
3. Bundled GRE starter sample.
4. Configured API from `DICTIONARY_API_BASE_URL`.
5. Public online dictionaries: dictionaryapi.dev, then Wiktionary.
6. Mock dictionary for known sample words.

To add an ECDICT-compatible local CSV without editing environment variables, use:

```text
Add / Import -> ECDICT Local Dictionary -> Choose ECDICT CSV -> Test ECDICT -> Save Dictionary Path
```

The path is stored in the local SQLite `settings` table. The large ECDICT CSV itself is not copied into this repository or uploaded to GitHub.

You can still use an environment variable as fallback:

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

Online English-only dictionaries fill English, phonetic, part of speech, example, source, and notes. They do not auto-fill the Chinese field; use the English definition shown in Notes as a reference and write your own Chinese meaning before adding. The Manual Add form also has an `Add to deck` selector so a looked-up word can be saved to any active deck.

Expected useful JSON fields include `english` or `word`, `chinese` or `translation` or `meaning` or `definition`, `pos` or `partOfSpeech`, `phonetic`, `example`, and `source`.

## Optional AI Provider

By default VocaBoost uses `MockAiService`, so review explanations work offline.

Recommended setup from the UI:

```text
Add / Import -> AI Explanation Provider
Provider: openai-compatible
Base URL: https://your-provider.example/v1/chat/completions
API key: your-key
Model: your-model
Save AI Settings -> Test AI Explanation
```

The saved settings stay in the local SQLite database and take effect immediately. They are not committed to Git because the database is ignored.

You can also use environment variables before launching:

```powershell
$env:VOCABOOST_AI_PROVIDER = 'openai-compatible'
$env:VOCABOOST_AI_BASE_URL = 'https://your-provider.example/v1/chat/completions'
$env:VOCABOOST_AI_API_KEY = 'your-key'
$env:VOCABOOST_AI_MODEL = 'your-model'
mvn javafx:run
```

The app never commits API keys. UI-saved keys stay in the local ignored SQLite database; environment keys stay outside the project. Responses are cached in the local `ai_cache` table by word, and failures fall back to Mock AI instead of blocking review. Use `Add / Import -> AI Explanation Provider -> Test AI Explanation` to verify configuration.

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

For a larger GRE list, prepare your own CSV in the format above and use `Add / Import -> Import GRE CSV`. Unknown or copyrighted 2000-word lists are intentionally not bundled. The bundled starter is an original, self-maintained sample of common GRE-style study words; use your own licensed CSV for a full 2000-word deck.

GRE CSV import stops at 2000 imported words. The one-click GRE starter import refreshes Dashboard, Word List, and Review immediately after import.

## Build a Clickable Windows App

For a click-to-run Windows app folder with `VocaBoost.exe`, use:

```powershell
.\scripts\package-windows.ps1
```

The packaging script uses the normal Maven cache by default. To force a custom local Maven cache, set `VOCABOOST_MAVEN_REPO`; project-local `.m2/` remains ignored by Git.

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
docs/          architecture, roadmap, screenshot placeholders
```

More documentation:

- `docs/ARCHITECTURE.md`
- `docs/ROADMAP.md`

Business logic does not depend on `Scanner`, `System.out`, `System.exit()`, or JavaFX controls.

## Test Coverage

Current tests cover:

- `SimilarityService`
- `ReviewScheduler`
- `WordSelector`
- `WordValidationService`
- `DeckService`
- `GoalService`
- `AchievementService`
- `DictionaryService`
- `LocalDictionaryService`
- `SettingsRepository`
- `AiServiceFactory`
- `CachingAiService`
- `MockAiService`
- `ImportExportService`
- `ReviewService`
- `StatsService`
- `BackupService`
- SQLite repository CRUD and new persistence tables

Verified command:

```powershell
& 'C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.1.1\plugins\maven\lib\maven3\bin\mvn.cmd' '-Dmaven.repo.local=.m2\repository' test
```

Latest local result: 45 tests, 0 failures.

## GitHub Actions

The repository includes `.github/workflows/maven-test.yml`, which runs `mvn test` on pushes and pull requests to `main`.
