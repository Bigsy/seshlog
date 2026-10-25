// Compose 1920x1200 Marketplace cards from real Seshlog components and synthetic demo data.
// See README.md in this directory for capture, render and upload instructions.
const fs = require('fs');
const path = require('path');
const root = path.join(__dirname, '..', 'screenshots');
const out = process.argv[2];
if (!out) throw new Error('Usage: node docs/marketplace/gen.js <html-output-directory>');
fs.mkdirSync(out, { recursive: true });
const png = name => 'data:image/png;base64,' + fs.readFileSync(path.join(root, name)).toString('base64');
const icon = fs.readFileSync(path.join(__dirname, '../../src/main/resources/META-INF/pluginIcon.svg'), 'utf8');

const css = `
*{box-sizing:border-box}html,body{margin:0;width:1920px;height:1200px;overflow:hidden}
body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Arial,sans-serif;background:#101917;color:#f4f5ed}
.page{position:relative;width:1920px;height:1200px;padding:64px 88px}
.top{display:flex;align-items:center;gap:18px;font-size:29px;letter-spacing:-.6px}
.top svg{width:48px;height:48px}.top strong{font-weight:650}.top span{margin-left:14px;color:#9eafa8;font-size:21px;letter-spacing:0}
.issue{margin-left:auto!important;letter-spacing:3px!important;font-size:18px!important;text-transform:uppercase}
.eyebrow{font-size:19px;text-transform:uppercase;letter-spacing:3px;font-weight:650;color:#b5df91;margin:0 0 28px}
h1{font-size:88px;line-height:1.03;letter-spacing:-4.3px;font-weight:650;margin:0 0 30px}h1 em{font-style:normal;color:#b5df91}
p{font-size:27px;line-height:1.46;color:#b1c0b9;margin:0}.small{font-size:21px;line-height:1.5;color:#9eafa8}
.copy{position:absolute;left:88px;top:205px;width:745px}
.pills{display:flex;gap:12px;flex-wrap:wrap;margin-top:32px}.pill{border:1px solid #3b5146;border-radius:8px;padding:10px 17px;color:#dce5de;font-size:20px}
.window{background:#1e1f22;border:1px solid #435047;border-radius:16px;overflow:hidden;box-shadow:0 30px 70px #0005}
.windowhead{display:flex;align-items:center;gap:10px;padding:20px 24px;background:#2b2d30;border-bottom:1px solid #3d4240;font-size:22px;color:#e4e8e3}
.windowhead .mini{margin-left:auto;color:#9daba4;font-size:17px}.window img{display:block;width:100%}
.screen{position:absolute;right:88px;top:205px;width:900px}.caption{font-size:19px;color:#9eafa8;margin-top:18px}
.action{background:#b5df91;color:#122218;border-radius:10px;padding:24px 26px;margin-top:20px;font-size:26px;font-weight:650;letter-spacing:-.3px;display:flex;align-items:center;gap:18px}
.action span{font-size:31px;font-weight:400}.actionlabel{font-size:18px;text-transform:uppercase;letter-spacing:2px;color:#a0b3a7;margin:26px 0 12px}
.foot{position:absolute;bottom:43px;left:88px;right:88px;padding-top:21px;border-top:1px solid #34453d;display:flex;justify-content:space-between;font-size:18px;color:#81968a}
.facts{margin:38px 0 0;padding:0;list-style:none}.facts li{font-size:25px;line-height:1.4;padding:22px 0;border-top:1px solid #34453d;color:#c8d5cc}.facts b{font-weight:600;color:#f4f5ed}
.steps{position:absolute;left:88px;top:475px;width:760px}.step{display:grid;grid-template-columns:54px 1fr;gap:20px;padding:26px 0;border-top:1px solid #34453d}.n{color:#b5df91;font-size:20px;padding-top:5px;font-family:Menlo,monospace}.step h2{font-size:31px;font-weight:600;margin:0 0 8px;letter-spacing:-.5px}.step p{font-size:23px}
.forkdiagram{display:flex;gap:20px;margin-top:24px}.node{flex:1;border:1px solid #3c5042;border-radius:12px;background:#17241e;padding:25px}.node b{display:block;font-size:25px;line-height:1.2;margin:14px 0 10px;font-weight:600}.node .tag{font-size:16px;letter-spacing:2px;color:#b5df91}.node p{font-size:21px;line-height:1.4}.node.new{border-color:#90b573;background:#233121}
`;

function shell(number, label, content, extra = '') {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><style>${css}${extra}</style></head><body><main class="page">
  <header class="top">${icon}<strong>Seshlog</strong><span>Coding-agent sessions in IntelliJ</span><span class="issue">${label}</span></header>
  ${content}<footer class="foot"><span>Local sessions · Real plugin UI · Synthetic demo data</span><span>${number} / 04</span></footer>
  </main></body></html>`;
}
const screen = (image, subtitle, cls = '') => `<div class="window ${cls}"><div class="windowhead">Seshlog<span class="mini">${subtitle}</span></div><img src="${png(image)}" alt="Seshlog session list rendered by the plugin"></div>`;

const cards = {
  '01-hero': shell('01', 'Worktrees + conversation forks', `
    <section class="copy"><div class="eyebrow">Claude Code &amp; Codex worktrees</div>
      <h1>Open the<br>worktree.<br><em>Keep the<br>conversation.</em></h1>
      <p>Open a session’s checkout in IntelliJ<br>and start an independent conversation<br>fork in a terminal there.</p>
      <div class="pills"><span class="pill">Original session keeps running</span></div>
    </section>
    <section class="screen">${screen('worktree-sessions.png', 'Sessions across worktrees')}
      <div class="actionlabel">From the session’s context menu</div>
      <div class="action"><span>↗</span>Open Worktree and Fork Session</div>
    </section>`),

  '02-worktrees': shell('02', 'From session to workspace', `
    <section class="copy"><div class="eyebrow">One action. A separate conversation.</div>
      <h1 style="font-size:76px">Your worktree.<br><em>Ready to continue.</em></h1>
    </section>
    <section class="steps">
      <div class="step"><span class="n">01</span><div><h2>Choose the session</h2><p>See its recorded worktree, branch<br>or detached HEAD before you open it.</p></div></div>
      <div class="step"><span class="n">02</span><div><h2>Open or focus its checkout</h2><p>A new IntelliJ window, or the existing one<br>if that worktree is already open.</p></div></div>
      <div class="step"><span class="n">03</span><div><h2>Continue in a fork</h2><p>Inherited conversation history. A new session ID.<br>Your original session keeps running.</p></div></div>
    </section>
    <section class="screen">${screen('worktree-sessions.png', 'Worktree details', 'detail')}
      <div class="action"><span>↗</span>Open Worktree and Fork Session</div>
      <div class="forkdiagram">
        <div class="node"><span class="tag">ORIGINAL</span><b>Keep your current thread</b><p>The running session<br>stays available.</p></div>
        <div class="node new"><span class="tag">FORK</span><b>Continue in the worktree</b><p>A separate terminal<br>and conversation.</p></div>
      </div>
      <p class="caption">Separate conversations share the same existing worktree files.</p>
    </section>`, `.detail{height:380px}.detail img{width:100%}.screen{top:240px}`),

  '03-sessions': shell('03', 'Browse + resume', `
    <section class="copy"><div class="eyebrow">Claude Code · Codex · opencode · Pi</div>
      <h1>Your agents.<br><em>One place<br>to pick up.</em></h1>
      <p>Find the right conversation across<br>your local coding-agent sessions.</p>
      <ul class="facts"><li><b>Browse by project</b><br>Titles, branches and recent activity.</li><li><b>See what needs attention</b><br>Working and waiting badges for tracked sessions.</li><li><b>Pick up the thread</b><br>Resume, fork or focus a running terminal.</li></ul>
    </section>
    <section class="screen">${screen('worktree-sessions.png', 'All four supported agents')}
      <p class="caption">Worktree location tracking for Claude Code and Codex.</p>
    </section>`),

  '04-search': shell('04', 'Search + rediscover', `
    <section style="position:absolute;left:88px;top:205px"><div class="eyebrow">Search the conversation</div>
      <h1 style="font-size:82px">Find the decision.<br><em>Resume the work.</em></h1>
    </section>
    <p style="position:absolute;right:88px;top:270px;width:610px">Search prompts and replies as well as titles and paths. Get back to the session with the context you need.</p>
    <section class="searchscreen window"><div class="windowhead">Seshlog<span class="mini">Conversation search</span></div>
      <img src="${png('search-field.png')}" alt="Search for checkout validation">
      <div class="searchresults"><img src="${png('session-search.png')}" alt="Matching conversations with content snippets"></div>
    </section>
    <div class="searchfacts"><span>Search across local sessions</span><span>Inspect matching messages</span><span>Resume with the context intact</span></div>
    `, `.searchscreen{position:absolute;left:88px;top:490px;width:1744px}.searchscreen img{width:100%}.searchresults{height:374px;overflow:hidden}.searchfacts{position:absolute;left:88px;right:88px;top:1045px;display:flex;gap:80px;font-size:22px;color:#b5df91}`),
};

for (const [name, html] of Object.entries(cards)) fs.writeFileSync(path.join(out, name + '.html'), html);
console.log(Object.keys(cards).join(' '));
