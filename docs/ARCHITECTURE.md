# VocaBoost Architecture

## Layers

- `app`: JavaFX startup and dependency wiring.
- `ui`: Programmatic JavaFX screens for dashboard, deck management, review, import, statistics, and word list.
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
