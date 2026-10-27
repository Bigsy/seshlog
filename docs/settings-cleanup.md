# Local search and diagnostics settings

Updated 2026-10-27 following the settings screenshot review.

## Search

`deaab14` (**Simplify local search settings**) replaces the crowded action row with a concise
index summary, Refresh and a Manage index menu containing Retry, Rebuild and Clear. Import and
Export are no longer offered in settings: the user prefers rebuilding the inexpensive local index.
The empty state avoids an unhelpful 0/0 coverage readout. Last-search coverage appears when relevant.
Completion messages wrap, and actions are disabled while maintenance is running.

Storage and privacy details are expandable, with a selectable index path and an Open index folder
button. The main section states that search stays on this device and the index can reveal conversation
content. The expanded disclosure covers stored terms/positions, no transcript copies/query history,
profile-local storage and exclusion from Settings Sync. Index maintenance behavior is unchanged;
internal storage-format and snapshot validation coverage remains in the suite.

Inspected synthetic dark renders at normal and 420-pixel narrow widths, including an expanded
privacy section and rebuild feedback. These are component renders, not physical IDE acceptance.
The temporary rendering fixture was removed. The presentation change passed `make check` with
571 tests, zero failures/errors and 10 opt-in skips.

## Diagnostics

**Make local session diagnostics optional** adds a persisted app-wide Record session diagnostics
checkbox. It defaults on for old installations, and Apply controls new event recording without
restarting the IDE. The producer and background writer both check the preference. Pending events
that reach the writer while disabled are skipped; an already-started write may finish. Existing
files are retained. Opening them while disabled does not enable recording or initialize a writer;
if none exist, the action explains how to enable local logging.

Settings explicitly say that diagnostics are stored only on this computer and are never uploaded
or sent anywhere. Record content and rotation limits remain unchanged. README and AGENTS describe
the opt-out and the distinction between opening existing logs and recording new ones.

Four regressions cover persistence/old-state defaults, configurable Apply/reset, no file creation
while disabled plus re-enabling, and queued versus in-flight writes. Logging tests use synthetic
temporary data; no agent-owned files were changed.

## Validation and remaining checks

`make check` and `make release` passed **575 tests, zero failures/errors, 10 opt-in skips**.
ZIP integrity and nested-JAR inspection passed with no test/fixture/transcript/database/model leakage.
The 0.8.1 artifact is **13,287,220 bytes**, SHA-256
`50d6a75f7c30aebd5ae0ffedc17865018fb4a357a7c8a0a30e1c9320f403839e`.

`./gradlew verifyPlugin --offline` passed with Verifier 1.411 on IU-262.10968.63 and IU-263.7307.44:
both **Compatible**. Warning counts remain 2 scheduled for removal each, 16/19 deprecated and 65/58
experimental usages. Optional IDE layout warnings remain. `git diff --check` passed.

Physical settings acceptance remains owed: keyboard access to the maintenance menu and privacy
section; resizing and light/dark rendering in the running IDE; diagnostic enable/disable with active
tracking, existing/absent logs and IDE restart. Existing switcher and search acceptance remain open,
and the search performance follow-up remains closed. No daily IDE installation or publication.
