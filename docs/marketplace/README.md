# Marketplace media

Upload these five 1920×1200 PNGs in this order in the Marketplace **Media** section:

1. `01-hero.png` — Open the worktree. Keep the conversation.
2. `02-worktrees.png` — Choose a session, open/focus its checkout, continue in an independent fork.
3. `03-sessions.png` — Browse and resume Claude Code, Codex, opencode and Pi sessions.
4. `04-search.png` — Search conversations and return to the session with the context you need.
5. `05-switcher.png` — Find a session across projects and switch terminals or worktrees with a shortcut.

The first two cards lead with worktree opening and conversation forks. A conversation fork uses
the existing checkout; it does not create a Git worktree or isolate file edits. Worktree location
tracking is advertised for Claude Code and Codex. The broader browser supports all four agents.

These are composed informational cards with actual Seshlog Swing components rendered in the
SDK's Darcula theme. All session titles, paths, activity and search snippets are synthetic demo
data. The surrounding captions and workflow diagrams are explanatory artwork, not extra plugin
controls. No real transcripts, usernames or machine paths are captured. The older raw screenshots
remain in `docs/screenshots/` for reference but are not used by this carousel.
The switcher capture includes all four agents, coloured branches, bold query matches and the
outlined selection. The search capture includes the source selector and You/Assistant match labels.

## Rebuild

From the repository root, capture current UI components with the opt-in renderer:

```sh
SESHLOG_SCREENSHOTS=1 \
SESHLOG_SCREENSHOT_DIR="$PWD/build/marketplace/raw" \
./gradlew test --offline --tests '*MarketplaceScreenshotTest*' --rerun-tasks

cp build/marketplace/raw/{worktree-sessions,session-search,search-field,session-switcher}.png docs/screenshots/
bash docs/marketplace/render.sh
```

The renderer uses in-memory demo sessions and never starts an agent or writes into agent storage.
It does nothing during ordinary test runs. `gen.js` creates self-contained HTML and `render.sh`
uses a disposable Chrome profile to render local pages. Set `CHROME_BIN` on other platforms.
Pass an output directory to `render.sh` to preview without replacing the checked-in cards.
Copy only the raw captures you intend to refresh; the first three cards share `worktree-sessions.png`.
Keep the shared design in `gen.js` when adding cards, and update its footer total and the list in
`render.sh`. The fifth-card update preserves cards 1–3 pixel-for-pixel apart from their footer counts.

Review all five PNGs at full size and at a smaller carousel size, check text clipping and action
wording, and verify that no unsupported feature is implied. The fixed 16:10 canvas is consistent
with [JetBrains' media guidance](https://plugins.jetbrains.com/docs/marketplace/best-practices-for-listing.html#media).

Updating these files or publishing a plugin ZIP does **not** replace Marketplace Media images.
The carousel still needs to be updated through the listing's admin page. No upload is performed
by these scripts.
