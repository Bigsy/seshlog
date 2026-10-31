# Codex terminal tracking validation

Updated 2026-10-31. C1–C6 are implemented; C7 acceptance remains open. This report distinguishes
automated coverage from physical checks. It does not accept the remaining search/worktree plan
or authorize starting Phase 2.

## Current identity contract

A captured thread-ID title is checked against a live, uniquely identified TUI and the session
index. Direct writable TUI-rollout evidence remains the fallback. Resume arguments, daemon-held
rollouts, working directories, counts, timestamps and fork ancestry cannot establish current
ownership. A recognized unindexed title displaces the previous identity while preserving the tab
reservation. Thread changes apply as generation-checked batches, including exchanges and cycles.

Copy and navigation validate current evidence before using a terminal. An uncertain active thread
cannot inherit its previous reply; ended-session Copy requires confirmed exit/no-agent evidence.
An observed process replacement cannot inherit an unchanged old title. See [usage](usage.md) for
the user-controlled title setting and conservative unresolved behavior.

## Implementation and automated evidence

Each reliability change was preceded by a failing regression and followed by `make check`.
Test counts include the ten skipped opt-in tests.

| Item | Commit | Result |
| --- | --- | --- |
| C1: launch intent versus current identity | `c60d29f` | 641 tests; both stale-resume regressions failed before the fix |
| C2: remove daemon timing/elimination inference | `4810055` | 641 tests; delayed-start plus `/new` failed before removal |
| C3: atomic ownership transitions | `e4aa550` | 648 tests; two-tab exchange failed before the fix |
| C4: action/process/title lifetime checks | `d07a2e3` | 655 tests; non-TUI stale-title and multiple-TUI regressions failed before the fix |
| C5: title-first bounded inspection | `c731fc2` | 659 tests; descriptor-count regressions failed before the change |
| C6: synthetic shared daemon and title switches | `7710673` | 661 tests; all four review scenarios exercise the real synthetic daemon/TUIs |

The C6 integration test uses stdout readiness barriers, an isolated marked profile, real writable
descriptor inspection, the provider, batch inspector, reconciler and Copy resolution. It covers
retained daemon rollouts, TUIs without persistent rollout writers, mixed title configuration,
controlled startup, exchanges and delayed publication. Parsing fixtures and helpers are test-only.
The live writable-descriptor regression passed with OS process enumeration available on this Mac.

Some incremental Gradle runs restored stale test constructor callers after Kotlin data-class
signature changes. A clean `compileTestKotlin --offline --no-build-cache`, followed by `make check`,
removed those stale outputs. The final `make check` run also passed all 661 tests (10 skips), with zero failures/errors. The opt-in real-history
benchmarks and `CodexLiveCheck` were not run. The latter now reports only aggregate existing-process
and descriptor evidence; daemon metadata never certifies terminal identity.

## Build and package

- `make release`: passed, including the full suite and ZIP verification.
- `./gradlew verifyPlugin --offline`: initially could not resolve uncached recommended IDE
  `263.7307.112`; this was a dependency availability failure.
- `./gradlew verifyPlugin`: passed against IU-262.11879.100 and IU-263.7307.112.
- IU-262: 2 scheduled-removal, 16 deprecated and 67 experimental API usages reported.
- IU-263: 3 scheduled-removal, 20 deprecated and 60 experimental API usages reported.
- The verifier also warned about missing platform-layout entries `findUsagesMl.jar` and an Android
  declarative-language module JAR. Both targets were reported compatible; API warnings remain.
- Inspected all 872 nested plugin-JAR entries: no sandbox helpers, fixtures, test classes,
  transcripts, databases or model files shipped.

Artifact: `build/distributions/seshlog-0.8.5.zip`.

```text
ZIP SHA-256  4151da6a442947365c7c9b9f7151dec394353e08a7f42ecb7b8659fbb8b607a5
JAR SHA-256  d08a9ed9f188f85aa727862463ae5c436b19f45d8eb1485d5bdd95f5cfb0f90b
```

Local build logs: `/tmp/seshlog-c7-release.log`, `/tmp/seshlog-c7-final-check.log` and
`/tmp/seshlog-c7-verifier-online.log`. Nothing was published or installed in the daily IDE.

## Physical environment and verification

All checks below used the generated profile `/private/tmp/seshlog-codex-tracking-sandbox`, created
2026-10-31T13:24:27Z. It has independent agent roots, IDE config/system/log/plugins and fictional
sessions. No real agent process was launched or agent configuration edited.

The documented macOS attachment wrapper preserved the original synthetic JVM arguments and
environment: `launcher/Seshlog Codex Sandbox.app`, bundle ID
`com.hedworth.seshlog.codexsandbox`. The attached window was `harbor – README.md`.

```sh
./gradlew runSyntheticIde --offline -PsyntheticSandboxDir=/private/tmp/seshlog-codex-tracking-sandbox
./gradlew -q verifySyntheticSandbox --offline -PsyntheticSandboxDir=/private/tmp/seshlog-codex-tracking-sandbox
```

Fresh verification passed at 2026-10-31T13:26:18.243752Z, before UI checks, for IDE PID 9532
(started 13:26:05.437Z), IU-262.10968.63, Java 25.0.4, macOS aarch64. Installed JAR matched the
hash above; no production source was newer. All scans used synthetic roots; initial counts were
147 Claude, 61 Codex, 13 opencode and 25 Pi. Both installed stub helper hashes matched source.
Evidence is in the profile's `verification.txt` and `/tmp/seshlog-c7-sandbox-verification.log`.

## Observed reworked-terminal results

The shared synthetic daemon ran in one terminal. Two additional terminals used
`SESHLOG_STUB_CODEX_DAEMON=1`; only the daemon held persistent rollout descriptors.

| Check | Physical observation |
| --- | --- |
| New title-enabled TUI | Adopted A, displayed Tracking A, and showed waiting activity after a fictional reply |
| `/new delayed` | Removed A's current activity, displayed Codex (unresolved), and Copy reported “No known session” |
| `/publish` and reply in B | Adopted B and displayed Tracking B; Copy reported B and pasted B's exact synthetic reply into unsent terminal input |
| Disable titles in the shared-daemon TUI | Removed running activity; Copy resolved no session. The tab retained its last-known display label |
| Confirmed `/exit` | Released B; Copy after exit still returned B's last reply |
| Reuse the same terminal | Launched `resume A`, then switched within the live TUI to B; ownership followed B despite stale A launch arguments |
| Mixed-title stale-resume reproducer | First TUI stayed B; second title-disabled TUI resumed A and stayed unresolved. Its Copy visibly reported “No known session,” never B's reply |
| Enable titles on second TUI | Adopted A independently while first TUI remained B |
| `/clear` in second TUI | Removed A's activity, displaced A, then adopted the new thread and changed the tab label to Untitled session |

Polling uses adaptive intervals, so row/tab updates were observed after the next poll; action
validation uses fresh evidence. These checks do not measure a latency SLA or comprehensive unread
acknowledgement behavior.

Diagnostics came only from `<profile>/ide/log/seshlog/`, run
`6a852740-8466-4eda-8641-964473e0fee3`, project reference `0b803d5447f16e893b4149ef`.
First TUI tab reference: `723522`; second: `3cc0ab87`. Session references:
A `4dcffd4d1fb69db34ef617f5`, B `5018ad72fa5225bed7ce9f04`.

Supporting UTC records:

- 13:29:39.562642, poll 44: `tab_identity_displaced` A after delayed `/new`.
- 13:29:51, Copy request 51: no resolved session while B was unindexed.
- 13:30:11, poll 56: adopted B after publication.
- 13:31:16, Copy request 72: resolved B and loaded `Found`; UI and pasted synthetic reply agreed.
- 13:32:17, Copy request 86: unresolved while titles were off.
- 13:32:41, poll 92: B released after observed process exit.
- 13:33:33.551, poll 105: displaced A and adopted B in the reused terminal, generation 6.
- 13:38:33.704, poll 162: displaced A from the second terminal after `/clear`.
- 13:38:41.151, poll 164: adopted new thread reference `91e6fd8fbe784ccbd06848bc`, generation 9.

No `diagnostic_gap` was present in the files inspected through 13:38:49 UTC. Logs remain local;
the report includes synthetic references rather than copied transcripts.

## Remaining acceptance

C7 is deliberately unchecked. Automated regressions do not replace these physical checks:

- Full classic-terminal matrix, including title and direct-writer paths.
- Physical simultaneous ownership exchange and delayed-start timing reproducer; both pass in
  the deterministic integration suite, but were not physically reproduced between IDE polls.
- Plugin new/resume/fork actions, Copy Session ID, switcher/Resume navigation, unread acknowledgement,
  split/move, two project windows and close/reuse while an action is in flight.
- Physical descriptor/enumeration failure injection and recovery, plus direct-writer fallback.
- Linux and Windows terminal/platform checks; neither OS is available in this session.

Attempts to invoke the session context menu through native automation did not produce a menu, and
Find Action did not expose Copy Session ID from the terminal context. Those action checks are
unverified, not passing. The completed shell `/resume` check does not certify the plugin Resume action.
The pre-existing IDE error indicator was not treated as evidence of a tracking failure; the scoped
diagnostic checks did not establish a platform/writer failure requiring broader `idea.log` inspection.

Keep the remaining Phase 1 responsiveness, relevance/resources, source-filter, accessibility and
worktree acceptance gates in PLAN.md open. Server-backed semantic search remains gated on Phase 1.

After the recorded checks, both synthetic TUIs were exited. During daemon cleanup the native
automation reported that the sandbox IDE had quit; the cause was not established, so IDE shutdown
is not an accepted check. The remaining synthetic daemon was identified by its exact marked-profile
command and terminated; its socket was removed. The stopped IDE’s diagnostic files were preserved
under `<profile>/acceptance-evidence/`. A fresh launch and verification are required before further UI checks.
