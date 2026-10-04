# VocaBoost Next Tasks

A short, prioritized list of tasks to pick up next, for contributors and coding agents. [docs/ROADMAP.md](docs/ROADMAP.md) has the full lists of what is done and what is not, and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) describes how the app works and why. Read the section of ARCHITECTURE.md that a task touches before you change it.

## Ground rules

- Keep the layers: `domain`, `repository` (all SQL), `service` (no JavaFX), `ui` (JavaFX only), `app` (wiring). The review flow belongs in `ReviewSessionPresenter`, not in the view.
- Schema changes are a new numbered step in `SchemaMigrations`, run in one transaction. Never delete user data. Existing databases must keep opening, and JSON backups from older versions must keep restoring.
- Every user-visible text goes into both `messages.properties` and `messages_zh_CN.properties`, with the same keys in the same order. `MessagesBundleTest` checks this.
- Services take a `Clock`. Tests use fixed clocks and must not depend on the time of day or the time zone.
- Before committing, run `./mvnw verify`. When you change the UI, also run `xvfb-run -a -s "-screen 0 1400x900x24" ./mvnw verify -Pui-tests`. If the change alters a screenshot, retake them with `xvfb-run -a -s "-screen 0 1400x900x24" ./mvnw test -Pscreenshots`.
- Update ARCHITECTURE.md (and README.md for user-visible changes) in the same change. Record a design decision where one was made.
- Never commit build output, local databases, API keys or personal paths.

## Next tasks

1. **OS credential store for the AI key.** Store the key in Windows Credential Manager, the macOS Keychain or the Secret Service on Linux when one is available, and keep the plain-text `settings` row as a documented fallback. Move existing keys out of `vocab.db` on first use. See ARCHITECTURE.md, API Keys.
2. **In-app snapshot restore.** Settings → Data and Logs lists the snapshots. Choosing one restarts into it: the swap happens at the next start, before the connection pool opens, after a snapshot of the current database. See ARCHITECTURE.md, Snapshots, for why it cannot be done while the app runs.
3. **macOS CI leg.** Add `macos-latest` to the packaging job (`scripts/package.sh`, then `scripts/smoke-test.sh` on `VocaBoost.app/Contents/MacOS/VocaBoost`). Signing and notarization can follow later.
4. **FSRS parameter optimization.** Fit the 19 FSRS-5 weights to the user's review logs (effective ratings, study-day intervals, `PRACTICE` and `KNOWN` logs excluded) once there is enough history. Keep the defaults otherwise, and show the measured retention next to the desired one on the Statistics tab.
5. **Separate schedules per direction.** Give EN→ZH and ZH→EN their own FSRS state, so that a word can be strong in recognition and weak in production. The logged `direction` makes a replay possible. The design must cover queues, due counts, backups and the Mixed and Cloze modes; see the Directions decision in ARCHITECTURE.md, Review Sessions.
6. **Semantic answer acceptance (English → Chinese).** Accept a typed meaning that ECDICT lists for the word, or a close synonym, even when it is not among the deck's meanings. Explain the acceptance under the answer, like the synonym case of Chinese → English.
7. **UTC timestamps.** Migrate the time columns to UTC instants (or add zone offsets) while keeping study days in local time. This is a large migration; see the Timestamps decision in ARCHITECTURE.md first.
8. **Text-to-speech fallback** for words without a recording, and a forecast that also simulates the reviews new words will add.
