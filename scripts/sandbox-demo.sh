#!/usr/bin/env bash
# Open the plugin sandbox on synthetic data: a demo Git repository with linked worktrees, plus
# Claude Code and Codex sessions recorded in them. Real agent folders are never read or written:
# CLAUDE_CONFIG_DIR, CODEX_HOME, PI_CODING_AGENT_DIR and XDG_DATA_HOME point at the demo.
# Rerunning rebuilds the demo, so deleted worktrees come back.
#
# Usage: scripts/sandbox-demo.sh [demo-directory]   (default: $TMPDIR/seshlog-demo)
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
demo="${1:-${TMPDIR:-/tmp}/seshlog-demo}"
marker=".seshlog-demo"

if [[ -e "$demo" ]]; then
  [[ -f "$demo/$marker" ]] || { echo "$demo exists and is not a Seshlog demo; choose another directory" >&2; exit 1; }
  if [[ -f "$demo/running.pid" ]]; then kill "$(cat "$demo/running.pid")" 2>/dev/null || true; fi
  rm -rf "$demo"
fi
mkdir -p "$demo"
demo="$(cd "$demo" && pwd -P)"
touch "$demo/$marker"

# Ignore the user's Git config (hooks, signing, templates) while building the demo repository.
git_demo() {
  GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null \
    git -c user.name="Seshlog Demo" -c user.email=demo@example.invalid -c init.defaultBranch=main "$@"
}

app="$demo/demo-app"
trees="$demo/worktrees"
mkdir -p "$app/src" "$trees"
printf '# Demo app\n\nSynthetic repository for testing Seshlog.\n' > "$app/README.md"
printf 'name,size\n' > "$app/src/app.csv"
printf '.idea/\n.claude/worktrees/\n' > "$app/.gitignore"
git_demo -C "$app" init -q
git_demo -C "$app" add -A
git_demo -C "$app" commit -q -m "Initial demo app"

add_worktree() { git_demo -C "$app" worktree add -q -b "$2" "$1"; }

add_worktree "$trees/feature-login" feature/login            # clean, branch fully merged
add_worktree "$trees/fix-parser" fix/parser                  # clean, branch has an unmerged commit
printf 'quoted,"values"\n' >> "$trees/fix-parser/src/app.csv"
git_demo -C "$trees/fix-parser" commit -q -am "Handle quoted values"
add_worktree "$trees/spike-ui" spike/ui                      # untracked file: git refuses
printf 'Sidebar ideas, not committed yet\n' > "$trees/spike-ui/notes.md"
add_worktree "$trees/old-experiment" experiment/cache        # locked: git refuses
git_demo -C "$app" worktree lock --reason "Demo: kept for reference" "$trees/old-experiment"
add_worktree "$trees/feature-search" feature/search          # has a running session: menu item disabled
add_worktree "$app/.claude/worktrees/tidy-docs" claude/tidy-docs  # Claude-style nested worktree

agents="$demo/agents"
claude="$agents/claude"
codex="$agents/codex"
mkdir -p "$claude/projects" "$claude/sessions" "$codex/sessions" "$agents/pi" "$agents/xdg"

# Last activity comes from the transcript's modification time.
stamp() { touch -t "$(printf '%s' "$1" | tr -d -- '-T:')" "$2"; }

claude_session() { # id cwd branch time title prompt reply
  local id=$1 cwd=$2 branch=$3 time=$4 title=$5 prompt=$6 reply=$7
  local file
  file="$claude/projects/$(printf '%s' "$cwd" | tr -c 'A-Za-z0-9' '-')/$id.jsonl"
  mkdir -p "$(dirname "$file")"
  cat > "$file" <<EOF
{"type":"user","sessionId":"$id","cwd":"$cwd","gitBranch":"$branch","timestamp":"${time}:00Z","message":{"role":"user","content":"$prompt"}}
{"type":"assistant","sessionId":"$id","cwd":"$cwd","gitBranch":"$branch","timestamp":"${time}:30Z","message":{"role":"assistant","content":[{"type":"text","text":"$reply"}]}}
{"type":"user","sessionId":"$id","cwd":"$cwd","gitBranch":"$branch","timestamp":"${time}:45Z","message":{"role":"user","content":"Looks good, thanks."}}
{"type":"ai-title","sessionId":"$id","aiTitle":"$title"}
EOF
  stamp "$time" "$file"
}

codex_session() { # id cwd branch time prompt reply
  local id=$1 cwd=$2 branch=$3 time=$4 prompt=$5 reply=$6
  local day=${time%%T*}
  local file="$codex/sessions/${day//-//}/rollout-${time//:/-}-00-$id.jsonl"
  mkdir -p "$(dirname "$file")"
  cat > "$file" <<EOF
{"timestamp":"${time}:00Z","type":"session_meta","payload":{"id":"$id","timestamp":"${time}:00Z","cwd":"$cwd","cli_version":"0.50.0","git":{"branch":"$branch"}}}
{"timestamp":"${time}:01Z","type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"<environment_context>\\n  <cwd>$cwd</cwd>\\n</environment_context>"}]}}
{"timestamp":"${time}:02Z","type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"$prompt"}]}}
{"timestamp":"${time}:30Z","type":"response_item","payload":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"$reply"}]}}
{"timestamp":"${time}:45Z","type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"Great, please add a test too."}]}}
EOF
  stamp "$time" "$file"
}

claude_session 0d3e0000-0000-4000-8000-000000000001 "$app" main 2026-10-20T09:00 \
  "Set up the demo app" "Create a small demo app with a CSV fixture" "Added README and src/app.csv."
claude_session 0d3e0000-0000-4000-8000-000000000002 "$trees/feature-login" feature/login 2026-10-27T10:00 \
  "Add login form" "Add a login form to the demo app" "Added the form and validation."
claude_session 0d3e0000-0000-4000-8000-000000000003 "$trees/feature-login/src" feature/login 2026-10-27T14:00 \
  "Login form tests" "Write tests for the login form" "Added three tests for the form."
codex_session 0d3e0000-0000-4000-8000-000000000004 "$trees/fix-parser" fix/parser 2026-10-28T11:00 \
  "Fix the CSV parser for quoted values" "Quoted values now parse correctly."
claude_session 0d3e0000-0000-4000-8000-000000000005 "$trees/spike-ui" spike/ui 2026-10-26T16:00 \
  "Try a new sidebar layout" "Sketch a new sidebar layout" "I wrote some notes in notes.md."
codex_session 0d3e0000-0000-4000-8000-000000000006 "$trees/old-experiment" experiment/cache 2026-10-15T09:30 \
  "Experiment with a response cache" "The cache works but needs eviction."
claude_session 0d3e0000-0000-4000-8000-000000000007 "$trees/feature-search" feature/search 2026-10-29T08:00 \
  "Add a search box" "Add a search box to the header" "Working on it now."
claude_session 0d3e0000-0000-4000-8000-000000000008 "$app/.claude/worktrees/tidy-docs" claude/tidy-docs 2026-10-25T12:00 \
  "Tidy the README" "Tidy up the README wording" "Shortened the introduction."

# A harmless process stands in for a running agent; its marker must be newer than the process.
nohup sleep 604800 >/dev/null 2>&1 &
running=$!
echo "$running" > "$demo/running.pid"
trap 'kill "$running" 2>/dev/null || true' EXIT
sleep 1
now_ms="$(date +%s)000"
printf '{"pid":%s,"sessionId":"0d3e0000-0000-4000-8000-000000000007","cwd":"%s","startedAt":%s,"version":"2.1.0","kind":"interactive","entrypoint":"cli","status":"busy","updatedAt":%s}\n' \
  "$running" "$trees/feature-search" "$now_ms" "$now_ms" > "$claude/sessions/$running.json"

cat <<EOF
Demo data: $demo
Worktrees to try (right-click the folder, Delete Worktree…):
  feature-login   clean, merged branch: deletes; "Also delete branch" removes feature/login
  fix-parser      clean, unmerged commit: deletes; the branch is kept
  spike-ui        untracked notes.md: git refuses
  old-experiment  locked: git refuses
  feature-search  running session: menu item disabled
  tidy-docs       nested under demo-app/.claude/worktrees: deletes
Rerun this script to reset. Closing the sandbox stops the fake running session.
EOF

cd "$repo_dir"
CLAUDE_CONFIG_DIR="$claude" CODEX_HOME="$codex" PI_CODING_AGENT_DIR="$agents/pi" XDG_DATA_HOME="$agents/xdg" \
  ./gradlew runIde --no-configuration-cache --args="$app"
