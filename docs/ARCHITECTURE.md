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

- `decks`: active/archived vocabulary collections; archived decks can be restored unless a name conflict exists. At least one deck always stays active. Startup opens the last used deck, falling back to the oldest active deck, and never looks a deck up by name, so the default deck can be renamed or archived.
- `words`: deck-scoped cards, scheduling state, metadata, tags, and archive flag.
- `review_logs`: typed answers, correct answers, similarity, self-rating, and response time.
- `daily_goals`: deck-scoped daily review/new-word/session goals, XP, and completion state.
- `achievements`: deck-scoped unlocked badge records.
- `dictionary_cache`: cached lookup payloads by English word.
- `settings`: local configuration such as saved ECDICT CSV path and load metadata, the last used deck (`ui.lastDeckId`), and whether the bundled starter words were already imported (`starter.imported`).
- `ai_cache`: cached AI explanations from the configured provider, keyed by word plus a hash of the endpoint, model, prompt version and word fields. Startup deletes rows that hold the mock fallback text, which older versions cached by mistake.

Schema changes are applied with `CREATE TABLE IF NOT EXISTS` and compatibility migrations. Existing local databases are not deleted.

Multi-step writes use `DatabaseManager.inTransaction` (exposed to services as `TransactionRunner`). It binds one connection to the calling thread; repository calls made inside the work, and nested `inTransaction` calls, join that transaction, and closing the joined connection is a no-op. The transaction commits only if all the work succeeds. Rating a card (schedule, review log, goal progress, achievements and their XP) and bulk word imports are atomic.

## Review Algorithm

The scheduler follows an SM-2 style model with easiness factor, interval days, repetitions, consecutive correct count, and lapses. User ratings are combined with answer similarity:

- high similarity keeps Good/Easy behavior normal,
- medium similarity caps the effective quality near Hard,
- low similarity behaves like Again and increases lapse pressure.

`WordSelector` prioritizes overdue, weak, low-interval, high-lapse cards, then samples from weighted candidates.

## Similarity Algorithm

`SimilarityService` normalizes Chinese and English text, supports multiple Chinese meanings, and combines token overlap/Jaccard-style matching with edit-distance behavior. The highest matching meaning is used for scheduling.

## Dictionary Services

`DictionaryService` is composed in this order:

1. saved ECDICT CSV path from the local `settings` table,
2. environment fallback `ECDICT_CSV_PATH`,
3. bundled GRE starter sample,
4. optional configured API through `DICTIONARY_API_BASE_URL` / `DICTIONARY_API_KEY`,
5. public online dictionaries,
6. mock fallback.

Online lookups run in background JavaFX tasks and cached results can be refreshed from the UI. The ECDICT CSV is never copied into the repository; only the local path is stored.

## AI / Mock Design

`AiService` is an interface. `AiServiceFactory` chooses `MockAiService` by default and switches to `OpenAiCompatibleAiService` when AI settings exist in SQLite or when `VOCABOOST_AI_BASE_URL`, `VOCABOOST_AI_API_KEY`, and `VOCABOOST_AI_MODEL` are configured. The Add / Import tab can save provider, base URL, API key, and model to the local `settings` table, then reload the service immediately. The services are composed as `FallbackAiService(CachingAiService(OpenAiCompatibleAiService), MockAiService)`. `CachingAiService` wraps only the real provider, so `ai_cache` holds provider responses only; its key includes the base URL, model and `OpenAiCompatibleAiService.PROMPT_VERSION` (but not the API key), so changing the endpoint, model or prompt requests new explanations. `FallbackAiService` sits outside the cache and keeps review usable if the provider fails: that call shows the mock text with a failure note, nothing is cached, and the next call asks the provider again. **Test AI Explanation** calls the provider directly through `AiServiceFactory.createUncachedProvider`, without the cache or the fallback, and shows either the provider's response or its error.

## Review Sessions

`ReviewService` owns session state: active deck, selected mode, current mixed-mode question direction, target size, reviewed count, accuracy, XP, and unlocked achievements. A target of `0` means All Due. `ReviewMode.MIXED` chooses English-to-Chinese or Chinese-to-English per card, while weak-word mode keeps using weak-card selection. A card can only be rated after an answer was submitted; the submitted answer is kept until the rating is committed, so a failed save can be retried from the Review tab with the same answer and similarity.
