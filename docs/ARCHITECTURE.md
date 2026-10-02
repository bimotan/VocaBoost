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
- `words`: deck-scoped cards, metadata, tags, archive flag and FSRS card state (`card_state`, `stability`, `difficulty`, `learning_step`, with `next_review_at` as the due time, `last_reviewed_at`, `repetitions` and `lapses`). The SM-2 columns of older versions (`easiness_factor`, `interval_days`, `consecutive_correct`) are kept and still written; see Review Algorithm.
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

Reviews are scheduled with FSRS-5 and its published default parameters. The pure arithmetic is in `service.scheduling`: `Fsrs` (stability, difficulty, retrievability R = (1 + 19/81 · t/S)^-0.5 and the interval for the desired retention), `StudyDay` (the day rollover) and `CardScheduler` (states, steps, intervals and fuzz); `ReviewScheduler` applies them to a word.

- **Grades.** Again, Hard, Good and Easy are grades 1 to 4. The answer similarity caps the user's rating as before: below 55% it counts as Again, below 75% at most Hard, below 90% at most Good. The rating buttons show the interval each rating would give for the checked answer (`ReviewScheduler.preview`, nothing is written), e.g. "Good (3) · 4d"; after a wrong answer every button shows Again's interval.
- **States.** A card is `NEW`, `LEARNING`, `REVIEW` or `RELEARNING`. New cards go through learning steps of 1 and 10 minutes (Again restarts them, Hard repeats a step, Good moves on and graduates after the last, Easy graduates at once); a review card rated Again is a lapse and goes through a 10-minute relearning step. Graduating uses the FSRS stability as the interval.
- **Memory.** The first rating sets S and D from the grade. Later reviews use the time actually elapsed in whole study days: a success at low R raises S more than one at high R, so an overdue success grows the interval more than an early review, which barely changes it; a review on the same study day uses FSRS's short-term formula. Intervals aim at the desired retention (0.9), are capped at 36500 days and, from 3 days on, get a fuzz of a few percent derived from the card id and its review count (reproducible, but cards learned together spread out). Hard, Good and Easy always give increasing intervals.
- **Study day.** A study day starts at the rollover hour (4 am). Intervals count study days: a review due in 1 day is due from the start of the next study day, not 24 hours later. A learning card is due at its exact step time; any other card is due today when it is due before the next rollover. The dashboard's "Due today", the deck table and the review session use that rule (`WordCard.isDue`, `WordRepository.findDue`/`countDue`).
- **Settings.** `scheduler.desiredRetention` (0.7 to 0.97) and `scheduler.dayRolloverHour` (0 to 23) in the `settings` table override the defaults; they are read at startup and invalid values are logged and ignored.
- **Memory, mastered, weak, leeches.** The Word List's Memory column, the memory chart and the selector weights use R now ("New" for a card never reviewed). A word is mastered when it is in review with S of at least 21 days, whatever lapses it had. A word is weak when it is relearning, was rated Again within its last 3 reviews (`repetitions > consecutive_correct` and `consecutive_correct < 3`), or has D of 7 or more and is not mastered; an old lapse alone no longer makes a word weak. Both rules exist as `WordCard` predicates and as SQL with the same thresholds, and `WordPredicateAgreementTest` keeps them in step. A word that lapses 8 times is tagged `leech` (and logged); the Review tab says so, its details show "Leech", and the Word List can filter leeches.
- **Next card.** `WordSelector` weights due cards by 1 - R, so the cards most likely forgotten come first; a new card weighs as much as a review card at its due date. The due query ranks learning cards, then review cards, then new cards, each by due time.
- **Response time.** `review_logs.elapsed_millis` is the time from showing the card to submitting the answer; the presenter records when the card was shown. Logs written by older versions hold the time from submitting to rating.

Schema version 5 adds the FSRS columns; the SM-2 columns stay and are still written (the interval as the scheduled interval, 0 while learning, and the easiness factor read from the difficulty), so an older version can still open the database and older backups still restore. Existing rows get no state (`card_state` NULL), and `CardStateBackfill` derives it at startup, in one transaction: a word with review logs is replayed log by log, with the logged rating and similarity at the logged time and without learning steps (the versions that wrote those logs had none); a word without logs is estimated from its SM-2 schedule (never reviewed: `NEW`; otherwise `REVIEW` with S = the interval, at least half a day, and D from the easiness factor, 2.5 to 5 and 1.3 to 9). A word an older version adds later is derived the same way on the next start. JSON backups carry the FSRS fields next to the SM-2 ones (format version 2, so older versions still read them); restoring a backup without them derives the state the same way, after its review logs are restored.

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

A session shows learning and relearning cards whose step time has come before anything else. When nothing is due, a learning card due within 20 minutes is shown early (Anki's learn-ahead), so a failed or new card is tested again in the same session. The session target counts different cards, not repeats; once it is reached, only cards this session left in learning come back, until none is due within the learn-ahead window.
