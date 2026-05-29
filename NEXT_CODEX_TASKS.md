# VocaBoost Next Codex Tasks

## 0. Current State Snapshot

Project: `D:\java\VocaBoost`

GitHub: `bimotan/VocaBoost`, default branch `main`.

Current product state:

- Java 17 + Maven + JavaFX + SQLite desktop app.
- Core tabs exist: Dashboard, Review, Add / Import, Statistics, Word List.
- SQLite database defaults to `%USERPROFILE%\.vocab-trainer\vocab.db`.
- Current default deck is created automatically.
- Empty database is seeded from bundled GRE starter sample.
- Review logs store typed answer, correct answer, similarity, rating, and response time.
- Goals / achievements exist in first-pass form: daily review goal, daily new-word goal, streak, XP, badges.
- Statistics exist in first-pass form: daily reviews, accuracy trend, memory distribution, hardest words, overdue count, Markdown report.
- Dictionary lookup exists in first-pass form: local starter data / optional ECDICT path / configured API / dictionaryapi.dev / Wiktionary / mock fallback.
- Tests currently cover repository CRUD, import, validation, dictionary cache, scheduler, selector, goals, achievements, similarity.

Repository hygiene status:

- `target/`, `.m2/`, `.idea/`, local DB files, env/API key files are ignored.
- Latest checked GitHub repo size was small, so build artifacts are not currently tracked.
- Keep this invariant: no generated build output, local Maven cache, IDE metadata, local SQLite DB, API keys, or packaged app images should be committed.

## 1. Development Direction

The app should move from "desktop prototype" toward "usable vocabulary learning product and portfolio project".

Do not rewrite the JavaFX + SQLite architecture. Continue the existing layered structure:

- `domain`: plain records/classes for app concepts.
- `repository`: SQLite schema and CRUD.
- `service`: testable business logic; no JavaFX controls, no `Scanner`, no `System.exit`.
- `ui`: JavaFX screens and UI orchestration only.
- `app`: dependency assembly and startup.

Engineering priorities:

1. Keep the app runnable after each milestone.
2. Run `mvn test` before commits.
3. Prefer small migrations with `CREATE TABLE IF NOT EXISTS` / compatible columns.
4. Preserve user data in existing SQLite databases.
5. Keep generated artifacts out of Git.

## 2. P0 - Repository And GitHub Hygiene

Goal: make the repository look clean and professional for GitHub / portfolio review.

Tasks:

- Verify no tracked files under `target/`, `.m2/`, `.idea/`, packaged `dist/`, local DB, or API key config.
- If any are tracked, remove from Git index without deleting user-local data unless explicitly safe:
  - `git rm -r --cached target .m2 .idea`
  - keep `.gitignore` rules.
- Add or verify `.gitignore` covers:
  - `target/`
  - `.m2/`
  - `.idea/`
  - `*.iml`
  - `*.db`, `*.db-shm`, `*.db-wal`
  - `.env`, `.env.*`, `*.key`, local API config.
- Add `docs/screenshots/.gitkeep` or screenshot placeholders only if actual screenshots are not committed yet.
- Do not commit generated `VocaBoost.exe` or runtime images.
- Final verification:
  - `git status --short`
  - `git ls-files target .m2 .idea`
  - `mvn test`

Acceptance criteria:

- GitHub repository clone stays lightweight.
- No generated build or local cache artifacts are tracked.
- README explains clean build and packaging commands.

## 3. P1 - Default GRE Data Quality

Goal: make the app immediately useful for testing and more credible as a vocabulary tool.

Tasks:

- Expand bundled starter data from about 20 words to 100-300 legally maintainable GRE-style sample entries.
- Use only original, public-domain, or clearly redistributable data.
- If full legal sourcing is uncertain, keep bundled sample modest and clearly document that users can import their own GRE CSV.
- Keep CSV format:
  - `english`
  - `chinese`
  - optional `pos`
  - optional `example`
  - optional `tags`
- Preserve current import limit of 2000 words.
- Ensure empty DB startup imports starter words only once.
- Add tests:
  - starter import imports at least 100 entries once expanded.
  - duplicate starter import skips duplicates.
  - malformed CSV rows are reported, not fatal.

Acceptance criteria:

- First launch shows usable words in Word List and Review.
- README explains bundled sample vs user-provided full GRE list.
- Import result summarizes imported / skipped / failed rows.

## 4. P2 - Dictionary Lookup That Is Actually Useful

Goal: make add-word lookup usable without requiring the user to configure an API key.

Current problem:

- Public English APIs often return English definitions, not Chinese meanings.
- Synchronous HTTP lookup can freeze JavaFX UI.
- "Format valid" and "real dictionary entry" should be separate states.

Target behavior:

- Validation states:
  - `INVALID`: bad format; block add.
  - `UNVERIFIED`: format valid, dictionary not found; allow force-add with tag `UNVERIFIED`.
  - `VERIFIED`: local or online dictionary found it; normal add.
- Lookup order:
  - local bundled English-Chinese sample.
  - optional local ECDICT CSV path via `ECDICT_CSV_PATH`.
  - configured API via `DICTIONARY_API_BASE_URL` / `DICTIONARY_API_KEY`.
  - dictionaryapi.dev and Wiktionary for phonetic, examples, English definitions.
  - mock fallback for demo words only.
- Online lookup must run on a background task:
  - disable Lookup button while loading.
  - show loading status.
  - update UI on JavaFX application thread after completion.
  - handle timeout / no network / bad JSON with a clear warning.
- Cache successful lookups in SQLite.

Implementation notes:

- Add a `DictionaryLookupTask` or use `javafx.concurrent.Task`.
- Keep HTTP and parsing in service layer.
- UI should call async wrapper, not block event handler.
- If dictionaryapi.dev returns English definitions only, populate candidate with a clear Chinese placeholder: "请填写中文释义".
- Do not pretend English definitions are Chinese meanings.

Tests:

- local dictionary verifies starter words.
- unknown word becomes `UNVERIFIED`, not `INVALID`.
- cache avoids repeated delegate lookup.
- HTTP parser handles dictionaryapi.dev-style nested JSON.
- lookup service returns failure object, not uncaught exception.

Acceptance criteria:

- Lookup button does not freeze UI during slow network.
- User can force-add valid unknown words as `UNVERIFIED`.
- User can still manually add words with clear validation errors.

## 5. P3 - Review Experience

Goal: make reviewing feel like a real study session rather than a single text box workflow.

Tasks:

- Add explicit review modes:
  - English to Chinese.
  - Chinese to English.
  - Weak words only.
- Keep multiple choice and spelling mode as later extensions unless time allows.
- Add session goal UX:
  - show current session progress, e.g. `8 / 15`.
  - use today's `sessionGoal` from `GoalService`.
  - when session goal is reached or no due cards remain, show a dedicated completion panel.
- Replace text-only completion summary with a clearer completion screen:
  - reviewed count.
  - accuracy.
  - XP earned.
  - today's goal progress.
  - unlocked achievement cards.
- Keep self-rating buttons: Again, Hard, Good, Easy.
- Continue saving rating and answer data to `review_logs`.

Similarity behavior:

- Keep Levenshtein + Jaccard score as an assistive signal.
- Display:
  - user answer.
  - correct answer.
  - character similarity.
  - optional keyword hit count.
  - user self-rating.
- Scheduling should combine self-rating and similarity:
  - high similarity: Good/Easy can apply normally.
  - medium similarity: cap quality around Hard.
  - low similarity: force Again-like scheduling and lower easiness.
- Do not use similarity as the only truth source for Chinese semantic correctness.

Tests:

- English-to-Chinese and Chinese-to-English answer normalization.
- low similarity caps rating.
- weak words mode prioritizes low similarity / Again / lapses.
- session summary counts reviews, accuracy, XP, achievements.

Acceptance criteria:

- Review flow is usable for at least one session without manual refresh.
- Completion screen has a clear "finished" state.
- Review history remains persisted and testable.

## 6. P4 - Performance And Responsiveness

Goal: keep the app smooth with 2000+ words and growing review logs.

Tasks:

- Replace broad `refreshAll()` usage with targeted refresh:
  - add/import word: refresh Dashboard, Word List, Review.
  - review card: refresh Dashboard and Review; defer Statistics until opened.
  - statistics tab: load charts when tab is selected or refresh button pressed.
- Add search debounce for Word List:
  - wait about 300 ms after user stops typing.
  - then query repository.
- Make heavy work backgrounded:
  - dictionary lookup.
  - large CSV import.
  - Markdown report export.
  - expensive statistics.
- Batch CSV import with SQLite transaction:
  - pre-load existing words for deck.
  - insert many rows in one transaction.
  - refresh UI once at the end.
- Add indexes as needed:
  - `review_logs(word_id, reviewed_at)`.
  - `review_logs(reviewed_at)`.
  - `words(deck_id, archived, next_review_at, lapses)`.
- Improve due-word selection:
  - avoid only sampling the first 50 due words forever.
  - query due + overdue + weak candidates with priority.

Tests:

- import transaction skips duplicates and commits valid rows.
- repository due candidate query includes overdue and weak words.
- stats queries still return correct aggregates.

Acceptance criteria:

- Importing 2000 CSV rows does not refresh UI per row.
- Lookup/import/report operations do not block JavaFX UI.
- Search does not query on every keystroke immediately.

## 7. P5 - Portfolio / Research Presentation

Goal: make the project convincing for GitHub and URAP-style review.

Tasks:

- Add a `Learning Analytics Report` section:
  - words whose review frequency increased due to weak performance.
  - words whose similarity improved over time.
  - interval growth over successful reviews.
  - overdue rescue count.
- Improve Markdown report export with:
  - summary metrics.
  - charts or chart-ready tables.
  - algorithm explanation.
  - top hard words and improved words.
- Add README screenshots:
  - Dashboard.
  - Review.
  - Add / Import.
  - Statistics.
- Add GitHub Actions:
  - run `mvn test` on push / PR.
  - optionally package Windows app on release.
- Add clear architecture diagram or section:
  - JavaFX UI.
  - service layer.
  - SQLite repositories.
  - local/online dictionary.
  - spaced repetition scheduler.

Acceptance criteria:

- README shows what the app does without needing to run it.
- GitHub Actions proves tests pass.
- Exported learning report looks usable for a portfolio attachment.

## 8. P6 - Multi-Deck Support

Goal: support different learning tasks such as GRE, course vocabulary, reading vocabulary, and mistakes.

Current state:

- Database has `decks`.
- Most repositories accept `deckId`.
- UI is effectively single default deck.

Tasks:

- Add deck management UI:
  - deck selector in header/sidebar.
  - create deck.
  - rename deck.
  - archive/delete deck with confirmation.
- All tabs must respect selected deck:
  - Dashboard.
  - Review.
  - Add / Import.
  - Statistics.
  - Word List.
- Import target should be selected deck.
- Empty app should create default GRE/sample deck.
- Goals and stats should become deck-aware or clearly global:
  - preferred: add `deck_id` to goal progress if feasible with migration.
  - minimum: document that goals are global while words/stats are deck-specific.
- Review session should reset or clearly switch context when deck changes.
- Add tests:
  - words in one deck do not appear in another.
  - due query is deck-scoped.
  - importing into selected deck does not affect other deck.
  - stats are deck-scoped.

Acceptance criteria:

- User can create at least two decks and switch between them.
- Word List and Review show only selected deck words.
- Dashboard and Statistics update when selected deck changes.

## 9. Data Safety And Backup

Goal: make local SQLite data safer for real users.

Tasks:

- Add export all data:
  - words CSV.
  - review logs CSV.
  - optional full JSON backup.
- Add import backup:
  - restore words.
  - preserve duplicate handling.
  - never silently overwrite without confirmation.
- Add button to open data directory.
- Keep displaying database path.
- README: explain where data lives and how to back it up.

Acceptance criteria:

- User can export all core learning data without opening SQLite manually.
- Backup import is tested on a temp database.

## 10. Suggested Next Execution Order

Use this order for the next Codex implementation runs:

1. P0 hygiene audit, because it protects GitHub quality.
2. P6 multi-deck support, because it affects almost every screen and should be settled before more features accumulate.
3. P2 async dictionary lookup, because it fixes a real UX bug.
4. P4 targeted refresh + lazy statistics, because it prevents future slowdowns.
5. P3 review modes and completion screen, because it improves daily usefulness.
6. P1 larger starter sample, as long as data source is legally safe.
7. P5 portfolio report and screenshots.
8. Data safety / backup.

## 11. Non-Negotiable Constraints For Future Codex Runs

- Do not hardcode API keys or tokens.
- Do not commit local generated artifacts.
- Do not delete user data; archive or migrate instead.
- Do not block the JavaFX application thread with network or heavy file work.
- Do not let business logic depend on JavaFX controls.
- Do not make unknown dictionary words impossible to add; mark them `UNVERIFIED`.
- Do not bundle copyrighted GRE or dictionary datasets without a verified license.
- Always run `mvn test` before committing.

## 12. Prompt Template For The Next Codex Run

```text
请继续在 D:\java\VocaBoost 当前 JavaFX + SQLite 项目上开发，不要推翻现有架构。先阅读 README.md 和 NEXT_CODEX_TASKS.md，再检查现有代码。

本轮优先实现：[填写 P0/P2/P3/P4/P6 中的一项或几项]。

要求：
- 保留 Java 17 + Maven + JavaFX + SQLite + JUnit5。
- 业务逻辑放 service/repository，UI 只做展示和交互。
- 不提交 target/、.m2/、.idea/、本地数据库、API key。
- 修改后运行 mvn test。
- 若需要推送，先确认 git status，commit 信息清楚，push 失败就停止报告。

请用中文回复，重点说明改了哪些文件、如何运行、测试结果、已知限制。
```
