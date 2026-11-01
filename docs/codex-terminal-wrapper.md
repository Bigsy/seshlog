# Automatic Codex title configuration in IDE terminals

Implemented 2026-10-31 for PLAN C8. The thread-ID title remains the identity signal; the wrapper
enables it for ordinary manually typed launches without editing Codex configuration.

The later merge `da634c1` also supplies a title setting directly for Seshlog's new/resume/fork
commands on macOS/Linux, including custom executables. Its 678-test suite passed (10 opt-in
skips); the package, compatibility and physical results below describe the earlier wrapper build.

## Behavior

For a new local bash, zsh or fish terminal on macOS/Linux, with IntelliJ shell integration enabled,
the registered `ShellExecOptionsCustomizer` prepends an IDE-managed directory to PATH. The platform
reapplies this addition after shell startup files, so a normal startup PATH reset does not undo it.
This applies to terminals opened by the user as well as Seshlog.

The directory contains one executable, `codex`. It searches the terminal's current PATH for the real
executable, excluding itself (including duplicate and symlinked entries), then uses `exec` to run:

```text
<real codex> -c 'tui.terminal_title=["project","thread-id"]' <original arguments>
```

Arguments are forwarded without evaluation. Stdin, stdout, stderr, exit status and process identity
belong to the real program. The wrapper's PATH entries are removed from the child environment to
prevent nested IDE wrappers cycling. An explicit subsequent configuration override remains effective.
Missing Codex produces a clear error and status 127; nothing is installed automatically.

The small script is extracted under the running IDE's config directory, at
`seshlog/terminal/codex-<content hash>/codex`, with owner-only execution permissions and atomic
replacement. Compatible files are reused. Versioned paths remain available to existing shells
after updates. The production resource `/terminal/codex` intentionally ships in the plugin;
synthetic sandbox helpers do not.

**Enable Codex tracking in new terminals** defaults on in Settings → Tools → Seshlog. Apply the
setting and reopen terminal tabs to change their behavior. Existing shells and agents are not
modified. User Codex config, shell startup files and transcripts are never edited by this feature.

## Limits

- Windows, unsupported shells, disabled shell integration and non-local Eel environments are
  left unchanged. Remote/container shells entered from a local terminal do not gain this wrapper.
- Aliases/functions, absolute executable paths and later PATH changes can bypass the wrapper.
  Seshlog's own launches on macOS/Linux supply the title setting directly, including custom
  full-path executables; manually typed full paths still bypass automatic setup.
- Explicitly overriding `tui.terminal_title` can remove the ID and leave shared-daemon tracking
  unresolved. The wrapper does not weaken the evidence requirements from [C1–C6](codex-tracking.md).
- The wrapper does not preserve custom title components from a config file: it supplies project
  and thread ID for the invocation. Users can pass their preferred title list including thread ID,
  or disable the wrapper and configure Codex themselves.

## Automated validation

`make check`: 675 tests, zero failures/errors, 10 opt-in skips. New coverage executes real synthetic
programs through `/bin/sh`: manual resume with empty/quoted/metacharacter/newline arguments, stream
and exit-status preservation, PATH order, non-executables, relative/empty entries, duplicate and
symlinked wrappers, nested IDE wrappers, explicit override order, missing binary, process identity
and repairing the installed script. Additional shell checks preserve existing aliases/functions
and later PATH/full-path overrides. Settings tests cover default migration, serialization and
Apply/Reset behavior.

The synthetic daemon integration test starts with titles disabled, launches `codex resume` through
the wrapper, checks the original thread ID, switches with `/new`, and checks the new title. It also
checks that the synthetic Codex config was unchanged. No real agent or model is used.

`make release` passed. ZIP inspection confirmed that the production launcher is present and there
is no sandbox helper or fixture leakage.

```text
ZIP SHA-256  bb224893e51c2bf1ade07a4aa29683d08d789b8f7e44479711f6b4d956ebc93d
JAR SHA-256  56353322d746ab8554a65c1374008247431936ebc6b0447d47ad1097d691ea58
```

Logs: `/tmp/seshlog-wrapper-final-check.log`, `/tmp/seshlog-wrapper-release.log`,
`/tmp/seshlog-wrapper-verifier.log`.

## Physical and compatibility acceptance

Offline Plugin Verifier passed on IU-262.11879.100 and IU-263.7307.112. It reports, respectively,
2/3 scheduled-removal, 16/20 deprecated and 79/72 experimental API usages. The terminal startup
customization API is experimental, so compatibility must continue to be checked on IDE upgrades.

Physical checks used `/private/tmp/seshlog-wrapper-sandbox`, generated 2026-10-31T14:08:56Z.
Fresh `verifySyntheticSandbox --offline` passed at 14:09:39.108230Z, before interaction, for the
attachable `launcher/Seshlog Wrapper Sandbox.app`, PID 29020, started 14:09:29.259Z. Runtime:
IU-262.10968.63, Java 25.0.4, macOS aarch64. All four provider roots/settings/scans were synthetic,
the installed JAR matched the hash above, and test-only helpers matched source. The report is
`<profile>/verification.txt` and `/tmp/seshlog-wrapper-sandbox-verification.log`.

The reworked-terminal checks passed:

- A new ordinary terminal loaded a synthetic `.zshrc` that reset PATH. `command -v codex` still
  selected the production wrapper, and `whence -a codex` showed only that wrapper and the synthetic
  stub. No real Codex executable was reachable through that command name.
- Manually typed `SESHLOG_STUB_CODEX_DAEMON=1 SESHLOG_STUB_TITLES=0 codex` emitted the current thread
  ID, was adopted and retitled Wrapper manual A, and Copy reported that thread.
- `/new` emitted a different ID; the tab was adopted as Wrapper manual B and Copy reported B.
  A multiline input attempt garbled one subsequent `/title` command, which was corrected by
  entering it separately; the readiness record and copied thread changed correctly.
- An isolated copy of the installed Oh My Zsh source (revision
  `17a2b4c3a5855524ad4772a17ada0dc474e2fdf3`) loaded the `git` plugin and `robbyrussell` theme.
  Its themed prompt rendered, `gst` remained `git status` and ran normally, and the wrapper stayed
  first in command lookup. Manual `codex resume <A>` with titles initially off emitted A's ID and
  was adopted separately from B. Exiting returned to the same themed prompt. Updates were disabled;
  framework copy, custom directory, completion cache and history were confined to the sandbox.
- The settings checkbox was turned off and applied. A new Oh My Zsh terminal then resolved
  `codex` directly to the synthetic stub, proving that the off switch takes effect on new shells.

Diagnostics: `<profile>/ide/log/seshlog/`, run `febc39e5-b156-4300-b5b7-3977bdf0c8db`, IDE PID 29020.
Tab `506324f8` adopted A (`bb4c51a885ccb76ee407daf1`) at 14:11:26 UTC/poll 23. Copy request 33
resolved A and loaded `Found`. Poll 35 displaced A after `/new`; poll 37 adopted B
(`ee23256c4862955c5acfe7a4`), generation 3. Copy request 58 resolved B and loaded `Found`.
The Oh My Zsh terminal `53ea0ad0` adopted A at 14:14:38/poll 69, generation 4.
No diagnostic gaps were recorded in the captured files. Both TUIs exited, the synthetic daemon
and IDE were stopped, and the daemon socket was removed. Diagnostic copies remain in the profile's
`acceptance-evidence/` directory. The daily IDE, real agent configs and personal shell files were untouched.

This validates a common Oh My Zsh configuration, not every custom theme/plugin. Classic-terminal,
fish and Linux physical checks remain unperformed; Windows is deliberately unsupported by this
wrapper. This report does not close the remaining C7 matrix or Phase 1 search/worktree gates.
