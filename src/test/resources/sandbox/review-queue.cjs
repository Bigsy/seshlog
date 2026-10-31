'use strict';
// Synthetic Codex 0.166.0-shaped queue. Only a marked sandbox's fictional sessions/files.
const fs = require('node:fs');
const path = require('node:path');
const net = require('node:net');
const crypto = require('node:crypto');
function run(config, guard) {
  const physical = guard(path.join(config.root, '.review-queue.sock'));
  const alias = guard(path.join(config.codex, 'app-server-control/app-server-control.sock'));
  const registrations = guard(path.join(config.root, '.review-terminals'));
  const queue = guard(path.join(config.root, '.review-queue'));
  const receipts = guard(path.join(config.root, 'review-receipts.jsonl'));
  fs.chmodSync(guard(config.root), 0o700);
  fs.mkdirSync(path.dirname(alias), { recursive: true, mode: 0o700 });
  if (fs.existsSync(physical)) throw new Error('synthetic review socket already exists; stop its server first');
  let order = 0;
  const clients = new Set();
  const log = (record) => fs.appendFileSync(receipts, JSON.stringify(record) + '\n');
  const lookup = (id) => {
    if (!fs.existsSync(registrations)) return null;
    const entries = fs.readdirSync(registrations).filter((name) => /^\d+\.json$/.test(name)).flatMap((name) => {
      try {
        const value = JSON.parse(fs.readFileSync(guard(path.join(registrations, name)), 'utf8'));
        process.kill(value.pid, 0);
        return value.id === id ? [value] : [];
      } catch { return []; }
    });
    return entries.length === 1 ? entries[0] : null;
  };
  const server = net.createServer((socket) => {
    clients.add(socket); socket.on('error', () => {}); socket.on('close', () => clients.delete(socket));
    let buffer = Buffer.alloc(0), upgraded = false;
    const send = (value) => {
      const body = Buffer.from(JSON.stringify(value));
      const header = body.length < 126 ? Buffer.from([0x81, body.length]) : body.length <= 65535
        ? Buffer.from([0x81, 126, body.length >> 8, body.length & 255]) : Buffer.concat([Buffer.from([0x81, 127]), Buffer.alloc(8)]);
      if (body.length > 65535) header.writeBigUInt64BE(BigInt(body.length), 2);
      socket.write(Buffer.concat([header, body]));
    };
    const request = (message) => {
      if (!Object.hasOwn(message, 'id')) return;
      const result = { id: message.id };
      const params = message.params ?? {};
      const reject = () => { result.error = { code: -32600, message: 'Synthetic receiver refused the request' }; send(result); };
      if (message.method === 'initialize') {
        result.result = { userAgent: 'seshlog-synthetic/0.166.0 (fixture)', codexHome: config.codex, platformFamily: 'unix', platformOs: process.platform };
      } else if (message.method === 'thread/read') {
        const target = lookup(params.threadId);
        if (!target) return reject();
        result.result = { thread: { id: target.id, cwd: guard(target.cwd), status: { type: target.busy ? 'active' : 'idle', activeFlags: [] } } };
      } else if (message.method === 'thread/queue/add') {
        const target = lookup(params.threadId);
        const text = params.input?.[0]?.text;
        if (!target || target.policy === 'refuse' || typeof text !== 'string' || Buffer.byteLength(text) > 128 * 1024 || !/^[a-f0-9-]{36}$/.test(target.id)) {
          log({ threadId: params.threadId, batchId: params.clientUserMessageId, result: 'rejected' });
          return reject();
        }
        const receipt = crypto.randomUUID();
        const directory = guard(path.join(queue, target.id)); fs.mkdirSync(directory, { recursive: true, mode: 0o700 });
        const file = guard(path.join(directory, `${Date.now()}-${String(order++).padStart(6, '0')}-${receipt}.json`));
        fs.writeFileSync(file, JSON.stringify({ threadId: target.id, batchId: params.clientUserMessageId, receipt, text }), { mode: 0o600 });
        log({ threadId: target.id, batchId: params.clientUserMessageId, receipt, bytes: Buffer.byteLength(text), result: target.policy === 'lost' ? 'lost' : 'queued' });
        if (target.policy === 'lost') return socket.destroy();
        result.result = { queuedSubmission: { id: receipt, clientUserMessageId: params.clientUserMessageId, input: params.input } };
      } else return reject();
      send(result);
    };
    socket.on('data', (data) => {
      try {
        buffer = Buffer.concat([buffer, data]);
        if (buffer.length > 1024 * 1024) return socket.destroy();
        if (!upgraded) {
          const end = buffer.indexOf('\r\n\r\n');
          if (end < 0) { if (buffer.length > 16384) socket.destroy(); return; }
          const header = buffer.subarray(0, end).toString('ascii');
          const key = header.split('\r\n').find((line) => /^sec-websocket-key:/i.test(line))?.split(':').slice(1).join(':').trim();
          if (!key) return socket.destroy();
          const accept = crypto.createHash('sha1').update(key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64');
          socket.write(`HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${accept}\r\n\r\n`);
          buffer = buffer.subarray(end + 4); upgraded = true;
        }
        while (buffer.length >= 2) {
          const opcode = buffer[0] & 15;
          if (!(buffer[0] & 0x80) || !(buffer[1] & 0x80)) return socket.destroy();
          let length = buffer[1] & 127, offset = 2;
          if (length === 126) { if (buffer.length < 4) return; length = buffer.readUInt16BE(2); offset = 4; }
          else if (length === 127) { if (buffer.length < 10) return; const large = buffer.readBigUInt64BE(2); if (large > 1024n * 1024n) return socket.destroy(); length = Number(large); offset = 10; }
          if (buffer.length < offset + 4 + length) return;
          const mask = buffer.subarray(offset, offset + 4), body = Buffer.from(buffer.subarray(offset + 4, offset + 4 + length));
          for (let i = 0; i < body.length; i++) body[i] ^= mask[i % 4];
          buffer = buffer.subarray(offset + 4 + length);
          if (opcode === 8) return socket.end();
          if (opcode === 10) continue;
          if (opcode !== 1) return socket.destroy();
          request(JSON.parse(body.toString('utf8')));
        }
      } catch { socket.destroy(); }
    });
  });
  server.listen(physical, () => {
    fs.chmodSync(physical, 0o600);
    try { fs.unlinkSync(alias); } catch (e) { if (e.code !== 'ENOENT') throw e; }
    fs.symlinkSync(physical, alias);
    console.log('SESHLOG_REVIEW_SERVER_READY');
  });
  const stop = () => {
    for (const client of clients) client.destroy();
    server.close(); fs.rmSync(alias, { force: true }); fs.rmSync(physical, { force: true }); process.exit(0);
  };
  for (const signal of ['SIGTERM', 'SIGINT', 'SIGHUP']) process.on(signal, stop);
}
module.exports = { run };
