# VocaBoost Architecture

## Layers

- `app`: JavaFX startup and dependency wiring. `AppServices` opens one database and wires its repositories and services; the app and the UI tests both build the window through it.
- `ui`: Programmatic JavaFX (no FXML). `MainWindow` is a thin shell that builds the `DeckHeader` and one view class per tab (`dashboard`, `decks`, `review`, `importing`, `stats`, `words` subpackages) on a shared `ViewContext`:
  - `DeckContext` holds the current deck and the active deck list and remembers `ui.lastDeckId`; a rename is not a deck switch.
  - Views never refresh each other. Whoever changes data publishes a `DataChange` (`WORDS`, `REVIEWS`, `DECKS`, `SETTINGS`) on `DataChanges`; Dashboard, Decks, Word List and Statistics use `LazyRefresh`, so a change only marks a hidden tab stale and it is recomputed when shown (Statistics on every visit, since it depends on the clock).
  - `UiAsync` runs slow work on `vocaboost-background-task` threads. Callers capture the deck, card or request the work is for before starting it, and pass trigger buttons that stay disabled while it runs; `LatestRequest` tickets drop results that arrive for something no longer shown.
  - The Review tab's flow lives in `ReviewSessionPresenter`, which has no JavaFX dependency (states `IDLE`, `AWAITING_ANSWER`, `ANSWERED`, `SAVING`, `RATING_FAILED`, `COMPLETE`); `ReviewView` only renders it and adds the keyboard shortcuts (Enter submits, then 1-4 or Space rate).
  - `UiErrors` logs and shows failures; every modal dialog and file chooser goes through the `Dialogs` interface (`JavaFxDialogs` in the app, a scripted fake in the UI tests), and key controls have stable ids (`node.setId`) that the UI tests look up.
- `service`: Review scheduling, goals, achievements, dictionary lookup, import/export, backup, validation, and analytics.
- `repository`: SQLite schema initialization, migration, and CRUD.
- `domain`: Records and entities such as `WordCard`, `Deck`, `ReviewLog`, goals, achievements, dictionary entries, and statistics rows.

Business logic stays out of JavaFX controls and does not use `Scanner`, `System.out`, or `System.exit`.

## SQLite Schema

- `decks`: active/archived vocabulary collections. Names are unique among active decks only (a partial unique index), so an archived deck's name can be reused; restoring an archived deck is refused while an active deck has its name. At least one deck always stays active. Startup opens the last used deck, falling back to the oldest active deck, and never looks a deck up by name, so the default deck can be renamed or archived.
- `words`: deck-scoped cards, scheduling state, metadata, tags, and archive flag.
- `review_logs`: typed answers, correct answers, similarity, self-rating, and response time. Indexed by `(word_id, reviewed_at, rating)`, which identifies one review (unique, so a restored backup never adds a review twice), and by `reviewed_at`.
- `daily_goals`: deck-scoped daily review/new-word/session goals, XP, and completion state. A row is written by the day's first review or new word; reading progress (dashboard, streak) never writes, and a day without a row reads as the default goals with no progress. The streak is computed with one query over the deck's review days.
- `achievements`: deck-scoped unlocked badge records.
- `dictionary_cache`: cached lookup payloads by English word.
- `settings`: local configuration such as saved ECDICT CSV path and load metadata, the last used deck (`ui.lastDeckId`), and whether the bundled starter words were already imported (`starter.imported`).
- `ai_cache`: cached AI explanations from the configured provider, keyed by word plus a hash of the endpoint, model, prompt version and word fields. The upgrade to schema version 1 deletes rows that hold the mock fallback text, which older versions cached by mistake.

The schema is versioned with `PRAGMA user_version` and upgraded by `SchemaMigrations` when `DatabaseManager.initialize()` runs. Version 0 is any database from before versioning: step 1 repeats what those releases did on every start (idempotent `CREATE`/`ALTER` steps, the deck-scope rebuild of `daily_goals` and `achievements`) and merges back rows that an interrupted rebuild left in `daily_goals_old` / `achievements_old`. Later steps rebuild `decks` without the old column-level `UNIQUE` (foreign keys off, same ids, `foreign_key_check` afterwards), collapse duplicate review logs and add indexes. Each step runs in one transaction together with its version bump, so an interrupted upgrade is retried on the next start; a new database goes through the same steps. Existing local databases are never deleted.

`DatabaseManager` pools connections: `getConnection()` lends one out and closing it returns it (statements the borrower left open are closed, a connection that cannot be reset is discarded). Each connection is set up once with `foreign_keys=ON`, `busy_timeout=5000` and `synchronous=NORMAL`, and the database uses `journal_mode=WAL`, so the UI can read while a background task writes. `VocabTrainerApp.stop()` closes the pool; tests close every database through the `TestDatabases` extension, which also fails a test that leaks a connection. Services do not run SQL themselves; queries live in the repositories.

Multi-step writes use `DatabaseManager.inTransaction` (exposed to services as `TransactionRunner`). It binds one pooled connection to the calling thread; repository calls made inside the work, and nested `inTransaction` calls, join that transaction, and closing the joined connection is a no-op. The transaction commits only if all the work succeeds. Rating a card (schedule, review log, goal progress, achievements and their XP), bulk word imports and backup restores are atomic.

## Review Algorithm

The scheduler follows an SM-2 style model with easiness factor, interval days, repetitions, consecutive correct count, and lapses. User ratings are combined with answer similarity:

- high similarity keeps Good/Easy behavior normal,
- medium similarity caps the effective quality near Hard,
- low similarity behaves like Again and increases lapse pressure.

`WordSelector` prioritizes overdue, weak, low-interval, high-lapse cards, then samples from weighted candidates.

## Similarity Algorithm

`SimilarityService` normalizes Chinese and English text, supports multiple Chinese meanings, and combines token overlap/Jaccard-style matching with edit-distance behavior. The highest matching meaning is used for scheduling.

## Dictionary Services

`DictionaryServiceFactory` composes the lookup chain:

1. the offline dictionaries (`LocalDictionaryService`): the imported ECDICT dictionary, then the bundled GRE starter words;
2. through the lookup cache (`CachingDictionaryService`, table `dictionary_cache`): the optional configured API (`DICTIONARY_API_BASE_URL` / `DICTIONARY_API_KEY`), the public online dictionaries and the mock fallback.

The offline dictionaries answer before the cache and are never cached, so a re-imported ECDICT or a better cleaner applies to every lookup, and an old cached copy of a raw ECDICT translation is never shown. Online lookups run in background tasks; "Refresh cache" looks the word up again.

### ECDICT

The ECDICT CSV is imported once into its own SQLite file next to the database, `ecdict.db` (`EcdictRepository`), so `vocab.db` and its backups do not grow by the dictionary's 100+ MB and the import needs no schema migration of `vocab.db`. The CSV itself is never copied into the repository.

- `ecdict(word TEXT PRIMARY KEY COLLATE NOCASE, phonetic, definition, translation, pos, collins, oxford, tag, bnc, frq, exchange, example)` holds the rows as the CSV has them (ECDICT is unique on the word ignoring case; when a word repeats, the first row wins). `example` is only filled from word-list CSVs.
- `ecdict_forms(form, lemma, kinds)`, primary key `(form, lemma)`, comes from the `exchange` field: the `p:`, `d:`, `i:`, `3:`, `r:`, `t:` and `s:` inflections of a base form, and the `0:` base form a row that is itself an inflection names.
- `ecdict_meta` records the source path, its size and modification time, the entry and skipped-row counts, the import time, the format (encoding, delimiter, columns) and the duration. `PRAGMA user_version` is the file's format; a file of another format reads as not imported and is imported again.

`EcdictImportService` streams the CSV through the shared `CsvReader` (quoting, ECDICT's literal `\n`, byte order marks, GBK, headerless files) and `EcdictColumns` (columns by header name, or ECDICT's order for a headerless file whose fourth field is Chinese, otherwise english, chinese, pos, example, tags). It writes 5,000-row batches in one transaction into `ecdict.db.importing` (no journal and no fsync: the file is thrown away if anything goes wrong) and swaps that file in place of `ecdict.db` only when every row is in. Lookups keep answering from the previous file during an import; a failure, a cancel or a crash leaves it as it was. Progress (rows and bytes) is reported every 10,000 rows and cancellation is checked every 1,000. A file that cannot be read fails with its line number, a header without a word or Chinese column and a file without entries are reported as such; nothing fails silently.

The ECDICT box on the Add / Import tab tests a file (encoding, columns and the first 200 rows; nothing is imported or saved), imports it in the background with a progress bar and a Cancel button, and saves the path only when the import succeeds. Opening the window never parses the CSV: it compares the saved path's size and modification time with `ecdict_meta` and imports in the background only when they changed or nothing is imported yet (for example after upgrading from a version that read the CSV at every start); without a saved path `ECDICT_CSV_PATH` is used. "Save and Import" skips an unchanged file, "Re-import" imports it anyway, and "Clear Dictionary Path" also deletes `ecdict.db`. The imported dictionary keeps working when the CSV is moved or deleted.

Lookups are indexed queries (`EcdictRepositoryTest` checks the query plans) that keep almost nothing in memory, and translations are cleaned as they are read (`EcdictTranslationCleaner`), so the add form gets a Chinese answer key the learner can type: ECDICT's literal `\n` separates lines, leading part-of-speech markers (`vt.`, `n.`, `adj.`, ...) become the part of speech ("verb; noun"), lines tagged `[网络]`, `[计]`, `[医]`, ... move to the note unless the word has nothing else, and the senses are joined with "; " without splitting inside brackets ("使(马,鹰等)戴头罩"). A meaning longer than 200 characters keeps its first senses and lists the rest in the note. A word that is not an entry but an inflection of one ("abandons") returns its base form, and the result says so.

## CSV Import and Export

Every CSV file is read and written through `service/csv`:

- `CsvReader` streams RFC 4180 records: quoted fields can hold the delimiter, doubled quotes and line breaks, and each record knows the physical line it starts on, so import messages name the line a text editor shows. It skips a byte order mark and empty lines, sniffs the delimiter (comma, tab or semicolon) from the first records, and reports a quote that is never closed with its line.
- `TextEncoding.detect` trusts a UTF-8 or UTF-16 byte order mark. Otherwise a file that is valid UTF-8 throughout is UTF-8, and anything else is read as GB18030, a superset of the GBK that Excel and Notepad on Chinese Windows write. A file whose first 16 non-ASCII characters are valid UTF-8 before a bad byte is still UTF-8 (one damaged or pasted-in character), so the import names that line instead of garbling every meaning as GB18030. Bytes that are invalid in the chosen encoding stop the read with their line number; they are never replaced.
- `WordColumns` maps columns by header name, ignoring case, spaces and underscores, with English and Chinese aliases from `WordColumn` (english/word/单词, chinese/meaning/释义, pos/词性, example/例句, phonetic/音标, note/备注, tags/标签). A first row that names at least two different fields is the header. A first row whose first cell names the English column but whose other names are unknown ("English,Meaning (中文)") is skipped as a header and the file is read by position. A file without a header is read as english, chinese, pos, example, tags. The local dictionary uses the same mapping, and reads a headerless file as ECDICT's own column order only when the first row has five or more fields with Chinese in the fourth.
- `CsvWriter` writes UTF-8 with a byte order mark (without it Excel on Chinese Windows shows the Chinese garbled), CRLF line ends, and quotes only where needed. `FormulaGuard` puts an apostrophe in front of cells that start with `=`, `+`, `-`, `@`, a tab or a carriage return, so a spreadsheet does not run them as formulas; plain numbers are left alone. The importer removes exactly that apostrophe, so "Export words CSV" (whose header uses the importer's column names) followed by "Import GRE CSV" reproduces every field.

"Import GRE CSV" loads the deck's English words once for the duplicate check, turns line breaks inside a meaning cell into "; ", lists at most 100 skipped-row messages, and stops at 2,000 imported words. "Preview GRE CSV" also shows the encoding, delimiter and column mapping it used. Errors name the file, the line and the reason, and keep the original exception as the cause.

## AI / Mock Design

`AiService` is an interface. `AiServiceFactory` chooses `MockAiService` by default and switches to `OpenAiCompatibleAiService` when AI settings exist in SQLite or when `VOCABOOST_AI_BASE_URL`, `VOCABOOST_AI_API_KEY`, and `VOCABOOST_AI_MODEL` are configured. The Add / Import tab can save provider, base URL, API key, and model to the local `settings` table, then reload the service immediately. The services are composed as `FallbackAiService(CachingAiService(OpenAiCompatibleAiService), MockAiService)`. `CachingAiService` wraps only the real provider, so `ai_cache` holds provider responses only; its key includes the base URL, model and `OpenAiCompatibleAiService.PROMPT_VERSION` (but not the API key), so changing the endpoint, model or prompt requests new explanations. `FallbackAiService` sits outside the cache and keeps review usable if the provider fails: that call shows the mock text with a failure note, nothing is cached, and the next call asks the provider again. **Test AI Explanation** calls the provider directly through `AiServiceFactory.createUncachedProvider`, without the cache or the fallback, and shows either the provider's response or its error.

## Review Sessions

`ReviewService` owns session state: active deck, selected mode, current mixed-mode question direction, target size, reviewed count, accuracy, XP, and unlocked achievements. A target of `0` means All Due. `ReviewMode.MIXED` chooses English-to-Chinese or Chinese-to-English per card, while weak-word mode keeps using weak-card selection. A card can only be rated after an answer was submitted; the submitted answer is kept until the rating is committed, so a failed save can be retried from the Review tab with the same answer and similarity.
