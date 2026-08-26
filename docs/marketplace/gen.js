// Marketplace card generator. Raw captures live in docs/screenshots/; this composes them on a
// branded backdrop and Chrome renders each HTML card at 1920x1200:
//   node docs/marketplace/gen.js /tmp/cards && for f in 01-hero 02-session-list 03-search 04-fork; do
//     "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --headless=new --disable-gpu \
//       --hide-scrollbars --force-device-scale-factor=1 --window-size=1920,1200 \
//       --screenshot=docs/marketplace/$f.png file:///tmp/cards/$f.html; done
const fs = require('fs'), path = require('path');
const root = path.join(__dirname, '..', 'screenshots');
const out = process.argv[2];
const b64 = f => 'data:image/png;base64,' + fs.readFileSync(path.join(root, f)).toString('base64');
const icon = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 13 13" fill="none"><path d="M6.5 1.5a5 4.5 0 0 1 0 9H4.2L1.8 12.2V9.3A4.5 4.5 0 0 1 6.5 1.5Z" stroke="#fff" stroke-width="1.1" stroke-linejoin="round"/><path d="M6.5 3.8V6l1.7 1" stroke="#fff" stroke-width="1.1" stroke-linecap="round" stroke-linejoin="round"/></svg>`;

const base = `
  html,body{margin:0;width:1920px;height:1200px;overflow:hidden;font-family:-apple-system,"SF Pro Display","Inter",Helvetica,Arial,sans-serif;color:#fff}
  body{position:relative;background:
    radial-gradient(1200px 700px at 15% 0%, rgba(99,102,241,.55), transparent 60%),
    radial-gradient(900px 600px at 95% 100%, rgba(14,165,233,.45), transparent 60%),
    linear-gradient(160deg,#0b1020 0%,#141a33 55%,#0b1020 100%)}
  .grid{position:absolute;inset:0;background-image:linear-gradient(rgba(255,255,255,.035) 1px,transparent 1px),linear-gradient(90deg,rgba(255,255,255,.035) 1px,transparent 1px);background-size:48px 48px;mask-image:linear-gradient(#000 40%,transparent)}
  .logo{width:64px;height:64px;border-radius:18px;background:linear-gradient(135deg,#6366f1,#22d3ee);display:grid;place-items:center;box-shadow:0 10px 30px rgba(99,102,241,.45);flex:none}
  .logo svg{width:38px;height:38px}
  .badge{display:inline-block;padding:6px 14px;border-radius:999px;background:rgba(255,255,255,.08);border:1px solid rgba(255,255,255,.15);font-size:16px;margin-bottom:18px;color:rgba(255,255,255,.85)}
  h1{margin:0 0 14px;font-weight:700;letter-spacing:-.02em;line-height:1.05}
  p{margin:0;line-height:1.35;color:rgba(255,255,255,.72)}
  .win{border-radius:14px;overflow:hidden;box-shadow:0 40px 90px rgba(0,0,0,.6),0 0 0 1px rgba(255,255,255,.08);background:#1e1f22}
  .win img{display:block}
  ul{list-style:none;padding:0;margin:40px 0 0}
  li{display:flex;gap:14px;align-items:flex-start;font-size:22px;line-height:1.35;color:rgba(255,255,255,.85);margin-bottom:18px}
  li b{font-weight:600;color:#fff}
  code{font-family:"SF Mono",Menlo,monospace;font-size:19px;background:rgba(255,255,255,.08);padding:2px 7px;border-radius:6px}
  li .dot{flex:none;width:10px;height:10px;border-radius:50%;background:linear-gradient(135deg,#6366f1,#22d3ee);margin-top:11px}
`;

// Hero: full IDE shot, warning row (y 968..1035 in the 1065px source) spliced out.
function hero() {
  const W = 1640, scale = W / 1920;
  const cutTop = 968, cutBottom = 1035, H = 1065;
  const topH = Math.round(cutTop * scale), botH = Math.round((H - cutBottom) * scale);
  const img = b64('04-resume-terminal.png');
  return `<!doctype html><html><head><meta charset="utf-8"><style>${base}
    header{position:absolute;left:100px;top:64px;right:100px;display:flex;align-items:flex-start;gap:28px}
    h1{font-size:52px} p{font-size:24px;max-width:1500px}
    .win{position:absolute;left:140px;top:260px;width:${W}px}
    .slice{width:${W}px;background-image:url(${img});background-size:${W}px auto;background-repeat:no-repeat}
  </style></head><body><div class="grid"></div>
  <header><div class="logo">${icon}</div><div><div class="badge">Seshlog · Coding Agent Session Manager</div>
    <h1>Every Claude Code session. One click to resume.</h1>
    <p>Seshlog lists your local coding-agent sessions with the titles the agent gave them — and reopens any of them in a terminal tab, right where you left off.</p></div></header>
  <div class="win"><div class="slice" style="height:${topH}px;background-position:0 0"></div>
  <div class="slice" style="height:${botH}px;background-position:0 -${Math.round(cutBottom * scale)}px"></div></div>
  </body></html>`;
}

function split(c) {
  return `<!doctype html><html><head><meta charset="utf-8"><style>${base}
    .text{position:absolute;left:100px;top:0;bottom:0;width:820px;display:flex;flex-direction:column;justify-content:center}
    .head{display:flex;gap:24px;align-items:flex-start}
    h1{font-size:60px} p{font-size:25px;max-width:760px}
    .win{position:absolute;right:100px;top:50%;transform:translateY(-50%);height:${c.h || 1000}px}
    .win img{height:${c.h || 1000}px}
  </style></head><body><div class="grid"></div>
  <div class="text"><div class="head"><div class="logo">${icon}</div><div><div class="badge">Seshlog · Coding Agent Session Manager</div><h1>${c.title}</h1><p>${c.sub}</p></div></div>
  <ul style="margin-left:88px">${c.bullets.map(b => `<li><span class="dot"></span><span>${b}</span></li>`).join('')}</ul></div>
  <div class="win"><img src="${b64(c.img)}"></div></body></html>`;
}

const cards = {
  '01-hero': hero(),
  '02-session-list': split({ img: '01-session-list-panel.png',
    title: 'Titled the way Claude titles them',
    sub: 'The same names you see in the terminal tab — no more guessing which anonymous transcript was which.',
    bullets: ['<b>Grouped by project</b>, sorted by last activity, with git branch and prompt count',
              '<b>Live indicator</b> for sessions that are running right now',
              '<b>Preview pane</b> shows the last messages without opening anything',
              '<b>Fork</b> a session into a new one from the context menu'] }),
  '03-search': split({ img: '02-search-panel.png',
    title: 'Search what was actually said',
    sub: 'Full-text search across your prompts and Claude’s replies, not just the titles.',
    bullets: ['Results <b>ranked by hits</b>, with a snippet of the first match',
              'Indexed lazily in the background — <b>100+ sessions in a few hundred ms</b>, then instant',
              'Reads Claude’s transcripts <b>read-only</b>; never writes to <code>~/.claude</code>'] }),
  '04-fork': split({ img: '03-context-menu.png', h: 700,
    title: 'Fork a session, keep the context',
    sub: 'Branch off an existing conversation into a new session — same history, new id — straight from the context menu.',
    bullets: ['<b>Fork Session</b> runs <code>claude --resume &lt;id&gt; --fork-session</code> in a new terminal tab',
              'Also on the <b>Terminal tab’s right-click menu</b> for any tab Seshlog knows the session of',
              '<b>Copy Resume Command</b> / <b>Copy Session ID</b> for scripting or sharing',
              '<b>Reveal in Finder</b> or open the raw transcript'] }),
};
for (const [f, h] of Object.entries(cards)) fs.writeFileSync(path.join(out, f + '.html'), h);
console.log(Object.keys(cards).join(' '));
