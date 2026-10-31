'use strict';
// Test-only local socket service. Holds only files inside a marked synthetic profile.
const fs = require('node:fs');
const path = require('node:path');
const net = require('node:net');
const socketPath = (config) => path.join(config.root, '.codex-daemon.sock');
function request(config, message) {
  return new Promise((resolve, reject) => {
    const socket = net.createConnection(socketPath(config));
    let input = '';
    socket.setTimeout(5000, () => socket.destroy(new Error('synthetic daemon timeout')));
    socket.on('error', reject);
    socket.on('connect', () => socket.write(JSON.stringify(message) + '\n'));
    socket.on('data', (data) => {
      input += data;
      if (input.includes('\n')) {
        socket.end();
        try { const result = JSON.parse(input.split('\n')[0]); result.error ? reject(new Error(result.error)) : resolve(result); }
        catch (e) { reject(e); }
      }
    });
  });
}
function run(config, guard) {
  const held = new Map();
  const address = guard(socketPath(config));
  if (fs.existsSync(address)) throw new Error('synthetic daemon socket already exists; stop the existing daemon first');
  const server = net.createServer((socket) => {
    let input = '';
    socket.on('data', (data) => {
      input += data;
      if (input.length > 16384) return socket.destroy();
      if (!input.includes('\n')) return;
      try {
        const message = JSON.parse(input.split('\n')[0]);
        if (message.op === 'hold') {
          const file = guard(message.file);
          if (!file.startsWith(guard(config.codex) + path.sep)) throw new Error('not synthetic Codex storage');
          if (!held.has(file)) held.set(file, fs.openSync(file, 'a'));
        } else if (message.op !== 'status') throw new Error('unknown synthetic operation');
        socket.end(JSON.stringify({ pid: process.pid, held: [...held.keys()] }) + '\n');
      } catch (e) { socket.end(JSON.stringify({ error: e.message }) + '\n'); }
    });
  });
  server.listen(address, () => {
    fs.chmodSync(address, 0o600);
    console.log('SESHLOG_DAEMON_READY ' + JSON.stringify({ pid: process.pid, socket: address }));
  });
  const stop = () => {
    for (const fd of held.values()) fs.closeSync(fd);
    server.close();
    fs.rmSync(address, { force: true });
    process.exit(0);
  };
  process.on('SIGTERM', stop); process.on('SIGINT', stop); process.on('SIGHUP', stop);
}
module.exports = { request, run };
