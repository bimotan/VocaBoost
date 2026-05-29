# VocaBoost Architecture

## Layers

- `app`: JavaFX startup and dependency wiring.
- `ui`: Programmatic JavaFX screens for dashboard, deck management, review, import, statistics, and word list.
- `service`: Review scheduling, goals, achievements, dictionary lookup, import/export, backup, validation, and analytics.
- `repository`: SQLite schema initialization, migration, and CRUD.
- `domain`: Records and entities such as `WordCard`, `Deck`, `ReviewLog`, goals, achievements, dictionary entries, and statistics rows.

Business logic stays out of JavaFX controls and does not use `Scanner`, `System.out`, or `System.exit`.

## SQLite Schema

- `decks`: active/archived vocabulary collections.
- `words`: deck-scoped cards, scheduling state, metadata, tags, and archive flag.
- `review_logs`: typed answers, correct answers, similarity, self-rating, and response time.
- `daily_goals`: deck-scoped daily review/new-word/session goals, XP, and completion state.
- `achievements`: deck-scoped unlocked badge records.
- `dictionary_cache`: cached lookup payloads by English word.
- `settings` and `ai_cache`: reserved extension tables.

Schema changes are applied with `CREATE TABLE IF NOT EXISTS` and compatibility migrations. Existing local databases are not deleted.

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

1. local GRE starter and optional `ECDICT_CSV_PATH`,
2. optional configured API through `DICTIONARY_API_BASE_URL` / `DICTIONARY_API_KEY`,
3. public online dictionaries,
4. mock fallback.

Online lookups run in background JavaFX tasks and cached results can be refreshed from the UI.

## AI / Mock Design

`AiService` is an interface. The current desktop app uses `MockAiService`, so no API key is required and the app remains fully usable offline.
