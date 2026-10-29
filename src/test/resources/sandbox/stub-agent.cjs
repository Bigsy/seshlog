'use strict';
// Stub agent CLIs for the Seshlog synthetic sandbox (see docs/synthetic-sandbox.md).
// Each prompt records a synthetic turn in the agent's own storage format, so Seshlog sees a
// running session go from working to waiting. Never contacts a model or the network, and refuses
// to touch any path outside the sandbox root baked into bin/lib/sandbox.json.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const readline = require('node:readline');

const STUB_VERSION = 'synthetic-stub';
const NAMES = { claude: 'Claude Code', codex: 'Codex', opencode: 'opencode', pi: 'Pi' };

function main(agent, config) {
  const root = realPath(config.root);
  const guard = (p) => {
    const resolved = realPath(p);
    if (resolved !== root && !resolved.startsWith(root + path.sep)) {
      throw new Error(`refusing to touch ${path.resolve(p)}: outside the synthetic sandbox ${config.root}`);
    }
    return path.resolve(p);
  };
  // Record the shell's spelling of the directory, as the real CLIs do, when it is the same place.
  const pwd = process.env.PWD;
  const cwd = pwd && path.isAbsolute(pwd) && realPath(pwd) === realPath(process.cwd()) ? pwd : process.cwd();
  let session;
  try {
    session = ADAPTERS[agent](config, guard).open(process.argv.slice(2), cwd);
  } catch (e) {
    console.error(`Synthetic ${NAMES[agent]} stub: ${e.message}`);
    process.exit(1);
  }
  let delayMs = Number(process.env.SESHLOG_STUB_DELAY_MS ?? 3000);
  console.log(`Seshlog synthetic ${NAMES[agent]} stub: session ${session.id}`);
  console.log(`Working directory: ${session.cwd}`);
  console.log('No model, network or real agent data is used. Type a prompt to record a synthetic turn.');
  console.log('Commands: /title <name>, /delay <seconds>, /exit');

  const rl = readline.createInterface({ input: process.stdin, output: process.stdout, prompt: `${agent}> ` });
  let turns = 0;
  let chain = Promise.resolve();
  let inputClosed = false;
  let closed = false;
  const finish = () => {
    if (closed) return;
    closed = true;
    try { session.close(); } finally { process.exit(0); }
  };
  const handle = async (line) => {
    const text = line.trim();
    if (text === '/exit' || text === '/quit') return rl.close();
    if (text.startsWith('/title ')) {
      session.rename(text.slice('/title '.length).trim());
      console.log('Title recorded.');
    } else if (text.startsWith('/delay ')) {
      delayMs = Math.max(0, Number(text.slice('/delay '.length)) * 1000 || 0);
      console.log(`Turns now take ${delayMs / 1000} s.`);
    } else if (text) {
      session.begin(text);
      console.log('(working)');
      await new Promise((resolve) => setTimeout(resolve, delayMs));
      turns += 1;
      const reply = `Synthetic reply ${turns}: recorded "${text.slice(0, 80)}". No model was contacted.`;
      session.finish(reply);
      console.log(reply);
    }
    if (!inputClosed) rl.prompt();
  };
  // Lines run one at a time, in order, including those still queued when input ends.
  rl.on('line', (line) => {
    chain = chain.then(() => handle(line)).catch((e) => console.error(`Synthetic ${NAMES[agent]} stub: ${e.message}`));
  });
  rl.on('SIGINT', () => rl.close());
  rl.on('close', () => { inputClosed = true; chain.then(finish); });
  for (const signal of ['SIGTERM', 'SIGHUP']) process.on(signal, finish);
  rl.prompt();
}

const ADAPTERS = {
  claude(config, guard) {
    const projects = guard(path.join(config.claude, 'projects'));
    const markers = guard(path.join(config.claude, 'sessions'));
    return {
      open(args, cwd) {
        const target = valueAfter(args, '--resume');
        let id = crypto.randomUUID();
        let file = path.join(projects, cwd.replace(/[^A-Za-z0-9]/g, '-'), `${id}.jsonl`);
        let records = [];
        if (!target) guard(cwd); // A new session records its working directory.
        if (target) {
          const source = findFile(projects, 2, (name) => name === `${target}.jsonl`);
          if (!source) throw new Error(`no synthetic session ${target}`);
          records = readJsonl(source);
          if (args.includes('--fork-session')) {
            file = path.join(path.dirname(source), `${id}.jsonl`);
            writeJsonl(guard(file), records.map((r) => ('sessionId' in r ? { ...r, sessionId: id } : r)));
          } else {
            id = target;
            file = source;
          }
        }
        guard(file);
        const sessionCwd = args.includes('--fork-session') ? guard(cwd) : (lastValue(records, 'cwd') ?? cwd);
        if (args.includes('--fork-session')) {
          append(file, { type: 'attachment', cwd: sessionCwd, sessionId: id, timestamp: new Date().toISOString() });
        }
        const branch = lastValue(records, 'gitBranch');
        let parent = lastValue(records, 'uuid') ?? null;
        const marker = path.join(markers, `${process.pid}.json`);
        const startedAt = Date.now();
        const status = (value) => {
          fs.mkdirSync(markers, { recursive: true });
          const record = { pid: process.pid, sessionId: id, cwd: sessionCwd, startedAt, version: STUB_VERSION,
            kind: 'interactive', entrypoint: 'cli', status: value, updatedAt: Date.now() };
          fs.writeFileSync(`${marker}.tmp`, JSON.stringify(record));
          fs.renameSync(`${marker}.tmp`, marker);
        };
        const message = (type, content) => {
          const uuid = crypto.randomUUID();
          append(file, { parentUuid: parent, isSidechain: false, type, message: { role: type, content }, uuid,
            timestamp: new Date().toISOString(), cwd: sessionCwd, sessionId: id, version: STUB_VERSION,
            ...(branch ? { gitBranch: branch } : {}) });
          parent = uuid;
        };
        status('idle');
        return {
          id, cwd: sessionCwd,
          begin(text) { status('busy'); message('user', text); },
          finish(reply) { message('assistant', [{ type: 'text', text: reply }]); status('idle'); },
          rename(name) { append(file, { type: 'custom-title', customTitle: name, sessionId: id }); },
          close() { fs.rmSync(marker, { force: true }); },
        };
      },
    };
  },

  codex(config, guard) {
    const sessions = guard(path.join(config.codex, 'sessions'));
    const names = guard(path.join(config.codex, 'session_index.jsonl'));
    return {
      open(args, cwd) {
        const [command, target] = args;
        let id = crypto.randomUUID();
        let file = rolloutPath(sessions, id);
        let sessionCwd = cwd;
        if (command === 'resume' || command === 'fork') {
          if (!target) throw new Error(`usage: codex ${command} <session id>`);
          const source = findFile(sessions, 4, (name) => name.endsWith(`-${target}.jsonl`));
          if (!source) throw new Error(`no synthetic session ${target}`);
          const records = readJsonl(source);
          const meta = records.find((r) => r.type === 'session_meta')?.payload ?? {};
          sessionCwd = command === 'fork' ? guard(valueAfter(args, '--cd') ?? valueAfter(args, '-C') ?? cwd) : (meta.cwd ?? cwd);
          if (command === 'fork') {
            const now = new Date().toISOString();
            writeJsonl(guard(file), [
              { timestamp: now, type: 'session_meta', payload: { ...meta, id, cwd: sessionCwd, timestamp: now, forked_from_id: target } },
              ...records.filter((r) => r.type === 'response_item' || r.type === 'event_msg'),
            ]);
          } else {
            id = target;
            file = source;
          }
        } else {
          guard(cwd);
          const now = new Date().toISOString();
          writeJsonl(guard(file), [{ timestamp: now, type: 'session_meta', payload: {
            id, timestamp: now, cwd, originator: 'codex_cli_rs', cli_version: '0.50.0', source: 'cli' } }]);
        }
        // Held open for the whole session, as the CLI holds its rollout: Seshlog matches writers.
        const fd = fs.openSync(guard(file), 'a');
        const write = (type, payload) =>
          fs.writeSync(fd, JSON.stringify({ timestamp: new Date().toISOString(), type, payload }) + '\n');
        const message = (role, kind, text) => write('response_item', { type: 'message', role, content: [{ type: kind, text }] });
        return {
          id, cwd: sessionCwd,
          begin(text) { write('event_msg', { type: 'task_started' }); message('user', 'input_text', text); },
          finish(reply) { message('assistant', 'output_text', reply); write('event_msg', { type: 'task_complete' }); },
          rename(name) { append(names, { id, thread_name: name, updated_at: new Date().toISOString() }); },
          close() { fs.closeSync(fd); },
        };
      },
    };
  },

  opencode(config, guard) {
    const database = guard(path.join(config.opencode, 'opencode.db'));
    let sqlite;
    try { sqlite = require('node:sqlite'); } catch { throw new Error('the opencode stub needs Node.js 22.5 or newer (node:sqlite)'); }
    return {
      open(args, cwd) {
        const db = new sqlite.DatabaseSync(database);
        db.exec('PRAGMA busy_timeout = 5000');
        const target = valueAfter(args, '--session');
        const now = Date.now();
        let id = `ses_${randomId()}`;
        let directory = cwd;
        if (target) {
          const row = db.prepare('SELECT * FROM session WHERE id = ?').get(target);
          if (!row) throw new Error(`no synthetic session ${target}`);
          directory = row.directory;
          if (args.includes('--fork')) {
            db.prepare(`INSERT INTO session (id, project_id, parent_id, slug, directory, title, version, time_created,
              time_updated, time_archived, agent, model) VALUES (?, ?, NULL, ?, ?, ?, ?, ?, ?, NULL, ?, ?)`)
              .run(id, row.project_id, `fork-${randomId()}`, row.directory, `${row.title} (fork)`, row.version, now, now, row.agent, row.model);
            for (const m of db.prepare('SELECT * FROM message WHERE session_id = ?').all(target)) {
              const messageId = `msg_${randomId()}`;
              db.prepare('INSERT INTO message VALUES (?, ?, ?, ?, ?)').run(messageId, id, m.time_created, m.time_updated, m.data);
              for (const p of db.prepare('SELECT * FROM part WHERE message_id = ?').all(m.id)) {
                db.prepare('INSERT INTO part VALUES (?, ?, ?, ?, ?, ?)').run(`prt_${randomId()}`, messageId, id, p.time_created, p.time_updated, p.data);
              }
            }
          } else {
            id = target;
          }
        } else {
          guard(cwd);
          db.prepare(`INSERT INTO session (id, project_id, parent_id, slug, directory, title, version, time_created,
            time_updated, time_archived, agent, model) VALUES (?, 'prj_synthetic', NULL, ?, ?, ?, '1.18.25', ?, ?, NULL, 'build', ?)`)
            .run(id, `new-${randomId()}`, cwd, 'New synthetic session', now, now, '{"providerID":"synthetic","modelID":"stub"}');
        }
        const message = (data, text) => {
          const at = Date.now();
          const messageId = `msg_${randomId()}`;
          db.prepare('INSERT INTO message VALUES (?, ?, ?, ?, ?)').run(messageId, id, at, at, JSON.stringify(data(at)));
          db.prepare('INSERT INTO part VALUES (?, ?, ?, ?, ?, ?)').run(`prt_${randomId()}`, messageId, id, at, at, JSON.stringify({ type: 'text', text }));
          db.prepare('UPDATE session SET time_updated = ? WHERE id = ?').run(at, id);
        };
        return {
          id, cwd: directory,
          begin(text) { message((at) => ({ role: 'user', time: { created: at }, agent: 'build' }), text); },
          finish(reply) { message((at) => ({ role: 'assistant', time: { created: at, completed: at }, finish: 'stop' }), reply); },
          rename(name) { db.prepare('UPDATE session SET title = ?, time_updated = ? WHERE id = ?').run(name, Date.now(), id); },
          close() { db.close(); },
        };
      },
    };
  },

  pi(config, guard) {
    const sessions = guard(config.pi);
    return {
      open(args, cwd) {
        const target = valueAfter(args, '--session');
        const forkSource = valueAfter(args, '--fork');
        let records = [];
        let file;
        if (target) {
          file = guard(target);
          if (!fs.existsSync(file)) throw new Error(`no synthetic session ${target}`);
          records = readJsonl(file);
        } else {
          const id = crypto.randomUUID();
          const stamp = new Date().toISOString().replace(/[:.]/g, '-');
          let header = { type: 'session', version: 3, id, timestamp: new Date().toISOString(), cwd };
          let directory = path.join(sessions, `--${cwd.replace(/^[/\\]/, '').replace(/[/\\:]/g, '-')}--`);
          if (forkSource) {
            const source = guard(forkSource);
            if (!fs.existsSync(source)) throw new Error(`no synthetic session ${forkSource}`);
            records = readJsonl(source);
            header = { ...records.find((r) => r.type === 'session'), id, timestamp: header.timestamp, parentSession: source };
            records = records.filter((r) => r.type !== 'session');
            directory = valueAfter(args, '--session-dir') ?? path.dirname(source);
          } else {
            guard(cwd);
          }
          file = guard(path.join(directory, `${stamp}_${id}.jsonl`));
          records = [header, ...records];
          writeJsonl(file, records);
        }
        const header = records.find((r) => r.type === 'session') ?? {};
        let leaf = lastValue(records.filter((r) => r.type !== 'session'), 'id') ?? null;
        const entry = (record) => {
          const id = crypto.randomBytes(4).toString('hex');
          append(file, { ...record, id, parentId: leaf, timestamp: new Date().toISOString() });
          leaf = id;
        };
        return {
          id: `pi:${header.id}`, cwd: header.cwd ?? cwd,
          begin(text) { entry({ type: 'message', message: { role: 'user', content: text, timestamp: Date.now() } }); },
          finish(reply) {
            entry({ type: 'message', message: { role: 'assistant', content: [{ type: 'text', text: reply }], stopReason: 'stop', timestamp: Date.now() } });
          },
          rename(name) { entry({ type: 'session_info', name }); },
          close() {},
        };
      },
    };
  },
};

/** Resolves symbolic links in the longest existing prefix, so /var and /private/var compare equal. */
function realPath(p) {
  let existing = path.resolve(p);
  const missing = [];
  while (!fs.existsSync(existing) && path.dirname(existing) !== existing) {
    missing.unshift(path.basename(existing));
    existing = path.dirname(existing);
  }
  return path.join(fs.realpathSync(existing), ...missing);
}

function valueAfter(args, option) {
  const index = args.indexOf(option);
  return index >= 0 ? args[index + 1] : undefined;
}

function findFile(directory, depth, matches) {
  if (depth < 0 || !fs.existsSync(directory)) return null;
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const child = path.join(directory, entry.name);
    if (entry.isFile() && matches(entry.name)) return child;
    if (entry.isDirectory()) {
      const found = findFile(child, depth - 1, matches);
      if (found) return found;
    }
  }
  return null;
}

function rolloutPath(sessions, id) {
  const now = new Date();
  const day = now.toISOString().slice(0, 10);
  const stamp = now.toISOString().slice(0, 19).replace(/:/g, '-');
  return path.join(sessions, ...day.split('-'), `rollout-${stamp}-${id}.jsonl`);
}

function readJsonl(file) {
  return fs.readFileSync(file, 'utf8').split('\n').flatMap((line) => {
    try { return line.trim() ? [JSON.parse(line)] : []; } catch { return []; }
  });
}

function writeJsonl(file, records) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, records.map((r) => JSON.stringify(r) + '\n').join(''));
}

function append(file, record) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.appendFileSync(file, JSON.stringify(record) + '\n');
}

function lastValue(records, key) {
  for (let i = records.length - 1; i >= 0; i -= 1) {
    if (typeof records[i][key] === 'string') return records[i][key];
  }
  return undefined;
}

function randomId() {
  return crypto.randomBytes(6).toString('hex');
}

module.exports = { main };
