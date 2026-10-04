# VocaBoost Roadmap

What VocaBoost does today, and what could come next. [ARCHITECTURE.md](ARCHITECTURE.md) describes how each completed part works and why it was built that way.

## Completed

### Learning and scheduling

- FSRS-5 with its published default parameters. New cards have learning steps of 1 and 10 minutes, lapses a 10-minute relearning step, and the desired retention can be set from 80% to 97%. Cards scheduled by the old SM-2 version get an FSRS state at the first start, replayed from their review history or estimated from their SM-2 schedule.
- Study days start at a rollover hour (4 am by default, configurable). Intervals, "due today", goals and the streak all count study days.
- The rating buttons preview the interval each rating would give.
- Typed answers are graded per direction:
  - English → Chinese compares meanings with Dice and Levenshtein similarity, ignoring part-of-speech markers and bracket tags.
  - Chinese → English allows a limited number of typos (Damerau-Levenshtein) and rejects known confusables and other words of the deck, unless they share the meaning.
- The answer check caps what a rating counts as, and "I was right" overrides it. Every review log keeps both the chosen and the effective rating.
- Review modes: English → Chinese, Chinese → English, Mixed (where a recognition Easy counts as Good), Weak Words practice (which does not stretch intervals) and Cloze (the word and its inflected forms blanked in the example sentence).
- Review queues: due learning steps first, then due reviews (most likely forgotten first) interleaved with new words up to each deck's daily limit. A learning card due within 20 minutes may be shown early.
- Sessions of 10, 20, 50, all due or a custom size. An All decks session reviews the due words of every deck.
- Undo (Ctrl+Z) of ratings, Already known and Suspend on the Review tab. The Word List can suspend and unsuspend words too.
- Leeches (8 lapses) are tagged, and a notice offers Suspend or an AI mnemonic.
- 215 starter words, each with a meaning no other starter word shares and an original example sentence of 12 to 25 words, imported once into a new database.

### Planning and analytics

- Daily review and new-word goals (every deck's or one deck's own), XP, badges, and one streak across decks that is read with a single query.
- Every daily number comes from the review logs, so the Dashboard, goals, charts, session summary and report always agree.
- Exam date (every deck's or one deck's own) with a countdown and a new-word plan. Reviews that would fall on or after the exam are moved into the last week before it.
- A 14- or 30-day workload forecast, with the exam day marked.
- Statistics (charts of daily reviews, accuracy and memory, the hardest words and the overdue count) and a Markdown learning report.

### Words, decks and dictionaries

- Decks: create, rename, archive and restore. Active deck names are unique, ignoring case, and the app reopens on the last used deck.
- Word List:
  - in-memory search and filters (status: due, weak, mastered, leech, suspended, unverified, unchecked; tag; part of speech), sorting by value, and an All decks view;
  - Phonetic and POS columns, and a details card with the example highlighted;
  - an edit dialog that checks the input while it is open.
- When a word already exists in another deck, adding it, importing it or building a tag deck asks whether to copy that deck's meaning, keep the new one or skip the word.
- ECDICT: the CSV is imported once into `ecdict.db` (streaming, cancellable, swapped in atomically), and its translations are cleaned into answer keys a learner can type. Decks can be built from ECDICT's exam tags (GRE, TOEFL, IELTS, CET-4/6, 考研, 高考, 中考).
- The dictionary lookup chain runs off the JavaFX thread:
  - Every lookup ends with a typed outcome: found, not found, network error, timeout, auth error, rate limited, service error, bad response, interrupted or offline.
  - dictionaryapi.dev and Wiktionary are asked at the same time, with a 6-second deadline.
  - Public results are cached for 30 days.
- Words that could not be checked are tagged `UNCHECKED`, and "Re-check unchecked words" checks them again later.
- Synonyms, antonyms and pronunciation playback from dictionaryapi.dev, using JavaFX Media.

### Import, export and data safety

- One CSV layer for all files: RFC 4180, UTF-8, UTF-16 and GBK detection, delimiter sniffing, and line numbers in error messages. Exported CSV cells are guarded against spreadsheet formulas.
- Word list import covers CSV, TSV, Anki plain-text exports (headers, HTML, cloze notes) and lists of words alone, whose meanings come from the local dictionaries (and, if you choose, the online ones). A preview lets you map columns. Legacy text files import too.
- Exports: Export for Anki (TSV; VocaBoost reads it back with the same fields except the note), a word list, words and review logs as CSV, and per-deck JSON backups.
- A JSON backup can be restored into the current deck (keeping its progress or using the backup's) or into a new deck. Never-reviewed words take the backup's progress, and review history is added only where the backup's schedule is used.
- Automatic database snapshots (before upgrades and restores, and daily; the newest 10 are kept), with documented manual restore steps.
- Versioned schema migrations (`PRAGMA user_version`), each step in one transaction, with a snapshot written first.
- SQLite in WAL mode with pooled connections and atomic multi-step writes. A fair writer lock keeps writes in order. Long background writes run in short batches, or pause the Review tab while a backup is restored, so a rating never fails waiting for them.
- The choice of local wall-clock timestamps is documented.

### AI explanations

- Optional OpenAI-compatible provider: structured explanations (meaning, feedback on the typed answer, memory tip, example) with lenient JSON parsing, and a cache keyed by word, answer, prompt version, endpoint and model.
- "Explain automatically" (always, only after a mistake, never), an Explain button and Regenerate.
- Failures are reported by category (auth, not found, rate limited, timeout, ...), and the offline explanation is shown instead.
- Offline mode stops all dictionary and AI requests.
- API keys are never shown again, kept out of logs and messages, sent only over https or to localhost, and never with redirects. The data folder is restricted to its owner.

### App

- A Settings tab holds the configuration. Every setting applies immediately, except the language, which applies at the next start.
- Simplified Chinese and English interface, with a glossary of the Chinese terms.
- Keyboard-driven review; a window that fits small laptops; WCAG AA contrast; a 100% / 115% / 130% text size; screen-reader labels and mnemonics.
- App icon, rotating log files, and error dialogs that say where the log is.

### Engineering

- Maven Wrapper, pinned plugins and enforcer rules. JaCoCo reports, with a 60% coverage floor for services and repositories.
- An FX-free review presenter. The UI tests drive the real window with scripted dialogs, without network access, on fixed clocks.
- CI on Windows and Linux with JDK 17 and 21. The UI tests run in a time zone where it is 1 am.
- Packaging scripts for Windows, Linux and macOS build a trimmed runtime (an app image of about 80 MB). A `--smoke-test` launch argument checks the packaged app in CI.
- The Windows release zip and its SHA-256 are published when a version tag is pushed. Dependabot proposes updates weekly.
- The docs screenshots are taken by a UI test, in English and Simplified Chinese.

## Next

None of these are implemented yet.

### Learning

- **Separate schedules per direction.** EN→ZH recognition and ZH→EN production could have their own schedules. Review logs already record the direction, so the history is there to split.
- **FSRS parameter optimization.** Fit the FSRS weights to the user's own review history instead of the published defaults, and show the measured retention next to the desired one.
- **Semantic answer acceptance for English → Chinese.** Accept a typed meaning that is a synonym of a listed one (for example, using ECDICT's other senses or a thesaurus), not only a similar string.
- **A multiple-choice recognition mode** in the style of GRE sentence equivalence, as a lighter alternative to typing.
- **Text-to-speech** for words without a recording from dictionaryapi.dev.

### Planning and analytics

- **Forecast the reviews that new words will add**, by simulating the schedule instead of counting only reviews already scheduled.
- **Richer analytics**: retention by tag or deck, response-time trends, and leech history.

### Data and privacy

- **UTC timestamps** (or stored zone offsets), so that moving between time zones or daylight-saving changes never reorder reviews. This needs a migration of every time column.
- **OS credential store for API keys** (Windows Credential Manager, macOS Keychain, Secret Service on Linux) instead of plain text in `vocab.db`.
- **In-app snapshot restore**: choose a snapshot and restart into it, instead of the manual steps.
- **Remember column mappings** between imports of files with the same layout.

### Platforms

- **macOS**: a CI packaging and smoke-test leg, code signing and notarization, and a published `.dmg`.
- **Linux release artifacts** (an app image archive or `.deb`) next to the Windows zip.
- **Sync between devices** and a **mobile companion app** for reviews on the go. Both need a sync format and conflict rules for review logs.
