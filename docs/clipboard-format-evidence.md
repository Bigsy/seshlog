# Plan format evidence

Inspected read-only from locally installed agent implementations on 2026-09-17. No real
transcripts or plan content are copied into this repository; fixtures are synthetic.
These are deliberately narrow supported formats, not keyword-based plan detection.

- Claude Code 2.1.273: `ExitPlanMode` normalization injects `plan` and `planFilePath`;
  its result schema carries nullable `plan`, `isAgent`, optional `filePath` and
  `planWasEdited`. A result is accepted only for the latest matching `tool_use_id`.
  Attachment construction uses `{type: "attachment", attachment: ...}` and
  `plan_file_reference` carries `planContent` and `planFilePath`. `plan_mode` reminders
  also name a path, but are not evidence of an existing plan and are not selected.
  Embedded text is a snapshot; file-only references are explicitly current-file reads.
- Codex's installed Plan Mode instructions define a standalone `<proposed_plan>` /
  `</proposed_plan>` envelope, containing Markdown. They explicitly distinguish
  `update_plan` checklist/progress state from the official plan. The reader accepts
  assistant dialogue envelopes, not reasoning, user text, tool arguments or event deltas.
- opencode's installed `plan_exit` implementation computes a path using its session
  service and worktree context. The tool result has empty `metadata`; a synthetic
  user message mentions the path in prose. This implementation does not reconstruct
  that version-dependent path or parse prose into a file association, so plan copying
  remains unsupported.
- Pi's installed plan-mode example extension persists custom `plan-mode` entries with
  `enabled`, `todos` and `executing` state. This is extension-defined checklist state,
  not a core, complete Markdown plan record. Plan copying remains unsupported.

For supported providers, transcript order defines revisions. A newer explicit but empty,
invalid, incomplete or unreadable source blocks fallback. Claude results from an older call
cannot replace a newer plan source. Reads are bounded and remain in memory; the action
shares the assistant-copy request guard and session targeting. Manual shortcut/terminal
checks remain recorded in the local PLAN.md.
