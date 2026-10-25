#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/../.." && pwd)"
output_dir="${1:-$repo_dir/docs/marketplace}"
mkdir -p "$output_dir"
output_dir="$(cd "$output_dir" && pwd)"
chrome_bin="${CHROME_BIN:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
if [[ ! -x "$chrome_bin" ]]; then
  echo 'Set CHROME_BIN to a Chrome/Chromium executable.' >&2
  exit 1
fi
render_dir="$(mktemp -d "${TMPDIR:-/tmp}/seshlog-marketplace.XXXXXX")"
trap 'rm -rf "$render_dir"' EXIT

node "$repo_dir/docs/marketplace/gen.js" "$render_dir/html"
for card in 01-hero 02-worktrees 03-sessions 04-search; do
  chrome_status=0
  "$chrome_bin" --headless=new --disable-gpu --no-first-run --no-default-browser-check \
    --disable-background-networking --disable-component-update --disable-sync --no-pings \
    --user-data-dir="$render_dir/profile" --hide-scrollbars --force-device-scale-factor=1 \
    --window-size=1920,1200 --screenshot="$render_dir/$card.png" \
    "file://$render_dir/html/$card.html" > "$render_dir/chrome.log" 2>&1 || chrome_status=$?
  # Some macOS Chrome builds exit nonzero during teardown after producing a complete screenshot.
  # Validate the fresh temporary output before copying it; never accept an older checked-in image.
  node - "$render_dir/$card.png" <<'JS' || {
const fs = require('fs'), zlib = require('zlib');
const png = fs.readFileSync(process.argv[2]);
if (png.subarray(0, 8).toString('hex') !== '89504e470d0a1a0a' ||
    png.readUInt32BE(16) !== 1920 || png.readUInt32BE(20) !== 1200) throw Error('Invalid screenshot');
let end = false, chunks = [];
for (let offset = 8; offset + 12 <= png.length;) {
  const length = png.readUInt32BE(offset), type = png.toString('ascii', offset + 4, offset + 8);
  if (offset + length + 12 > png.length) throw Error('Truncated PNG');
  if (type === 'IDAT') chunks.push(png.subarray(offset + 8, offset + 8 + length));
  if (type === 'IEND') end = true;
  offset += length + 12;
}
if (!end || !chunks.length || !zlib.inflateSync(Buffer.concat(chunks)).length) throw Error('Incomplete PNG');
JS
      cat "$render_dir/chrome.log" >&2
      exit 1
    }
  if [[ "$chrome_status" != 0 ]]; then
    echo "Chrome exited $chrome_status after rendering; accepted the validated $card.png." >&2
  fi
  cp "$render_dir/$card.png" "$output_dir/$card.png"
  echo "Rendered $output_dir/$card.png"
done
