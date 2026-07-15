import { createServer } from 'node:http';
import { timingSafeEqual } from 'node:crypto';
import WebSocket, { WebSocketServer } from 'ws';

const PORT = Number(process.env.PORT || 8787);
const RELAY_TOKEN = process.env.RELAY_TOKEN || '';
const ALLOWED_WS_HOSTS = new Set(
  (process.env.ALLOWED_WS_HOSTS || '')
    .split(',')
    .map((host) => host.trim().toLowerCase())
    .filter(Boolean),
);

if (!RELAY_TOKEN) {
  console.error('Missing RELAY_TOKEN. Set a strong token before starting the relay.');
  process.exit(1);
}

const server = createServer((req, res) => {
  if (req.url?.startsWith('/health')) {
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ ok: true }));
    return;
  }
  res.writeHead(426, { 'content-type': 'text/plain' });
  res.end('Expected WebSocket upgrade on /ws-proxy');
});

const wss = new WebSocketServer({ noServer: true });

server.on('upgrade', (req, socket, head) => {
  const url = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);
  if (url.pathname !== '/ws-proxy') {
    socket.write('HTTP/1.1 404 Not Found\r\n\r\n');
    socket.destroy();
    return;
  }

  if (!isValidToken(url.searchParams.get('token') || req.headers['x-relay-token'])) {
    socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
    socket.destroy();
    return;
  }

  const targetRaw = url.searchParams.get('target');
  const target = validateTarget(targetRaw);
  if (!target.ok) {
    socket.write(`HTTP/1.1 400 Bad Request\r\ncontent-type: text/plain\r\n\r\n${target.reason}`);
    socket.destroy();
    return;
  }

  wss.handleUpgrade(req, socket, head, (client) => {
    wss.emit('connection', client, req, target.url);
  });
});

wss.on('connection', (client, req, targetUrl) => {
  const targetLabel = targetUrl.toString();
  const requestUrl = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);
  const targetCookie = requestUrl.searchParams.get('target_cookie') || '';
  const protocolHeader = req.headers['sec-websocket-protocol'];
  const protocols = typeof protocolHeader === 'string'
    ? protocolHeader.split(',').map((p) => p.trim()).filter(Boolean)
    : undefined;

  const upstream = new WebSocket(targetLabel, protocols, {
    headers: {
      ...(req.headers.origin ? { origin: req.headers.origin } : {}),
      ...(req.headers['user-agent'] ? { 'user-agent': req.headers['user-agent'] } : {}),
      ...(req.headers['accept-language'] ? { 'accept-language': req.headers['accept-language'] } : {}),
      ...(targetCookie ? { cookie: targetCookie } : {}),
    },
  });

  console.log(`[ws-relay] OPEN ${targetLabel}`);

  client.on('message', (data, isBinary) => {
    logFrame('c→s', targetLabel, data, isBinary);
    if (upstream.readyState === WebSocket.OPEN) upstream.send(data, { binary: isBinary });
    else upstream.once('open', () => upstream.send(data, { binary: isBinary }));
  });

  upstream.on('message', (data, isBinary) => {
    logFrame('s→c', targetLabel, data, isBinary);
    if (client.readyState === WebSocket.OPEN) client.send(data, { binary: isBinary });
  });

  upstream.on('open', () => console.log(`[ws-relay] UPSTREAM_READY ${targetLabel}`));
  upstream.on('error', (err) => closeBoth(client, upstream, 1011, `upstream error: ${err.message}`));
  client.on('error', (err) => closeBoth(client, upstream, 1011, `client error: ${err.message}`));
  upstream.on('close', (code, reason) => closeBoth(client, upstream, code || 1000, reason?.toString() || 'upstream closed'));
  client.on('close', (code, reason) => closeBoth(client, upstream, code || 1000, reason?.toString() || 'client closed'));
});

server.listen(PORT, () => {
  console.log(`[ws-relay] listening on :${PORT}`);
});

function isValidToken(value) {
  const token = Array.isArray(value) ? value[0] : value;
  if (!token || typeof token !== 'string') return false;
  const a = Buffer.from(token);
  const b = Buffer.from(RELAY_TOKEN);
  return a.length === b.length && timingSafeEqual(a, b);
}

function validateTarget(raw) {
  if (!raw) return { ok: false, reason: 'Missing target' };
  let url;
  try { url = new URL(raw); } catch { return { ok: false, reason: 'Invalid target URL' }; }
  if (url.protocol !== 'ws:' && url.protocol !== 'wss:') {
    return { ok: false, reason: 'target must be ws:// or wss://' };
  }
  if (url.username || url.password) return { ok: false, reason: 'target credentials are not allowed' };
  const host = url.hostname.toLowerCase();
  if (ALLOWED_WS_HOSTS.size > 0 && !ALLOWED_WS_HOSTS.has(host)) {
    return { ok: false, reason: 'target host is not allowed' };
  }
  if (isBlockedHost(host)) return { ok: false, reason: 'private/local target host is blocked' };
  return { ok: true, url };
}

function isBlockedHost(host) {
  if (host === 'localhost' || host.endsWith('.localhost')) return true;
  if (/^(0|10|127)\./.test(host)) return true;
  if (/^169\.254\./.test(host)) return true;
  if (/^192\.168\./.test(host)) return true;
  if (/^172\.(1[6-9]|2\d|3[0-1])\./.test(host)) return true;
  if (host === '::1' || host.startsWith('fc') || host.startsWith('fd') || host.startsWith('fe80')) return true;
  return false;
}

function logFrame(direction, target, data, isBinary) {
  const buffer = Buffer.isBuffer(data) ? data : Buffer.from(data);
  if (!isBinary) {
    const text = buffer.toString('utf8');
    const preview = text.length > 1024 ? `${text.slice(0, 1024)}… (+${text.length - 1024} chars)` : text;
    console.log(`[ws-relay] ${direction} ${target} text ${buffer.length}B ${preview}`);
    return;
  }
  const hex = buffer.subarray(0, 256).toString('hex').replace(/(..)/g, '$1 ').trim();
  console.log(`[ws-relay] ${direction} ${target} binary ${buffer.length}B hex=${hex}${buffer.length > 256 ? ' …' : ''}`);
}

function closeBoth(client, upstream, code, reason) {
  const safeCode = code >= 1000 && code <= 4999 ? code : 1011;
  const safeReason = String(reason || '').slice(0, 120);
  if (client.readyState === WebSocket.OPEN || client.readyState === WebSocket.CONNECTING) {
    try { client.close(safeCode, safeReason); } catch {}
  }
  if (upstream.readyState === WebSocket.OPEN || upstream.readyState === WebSocket.CONNECTING) {
    try { upstream.close(safeCode, safeReason); } catch {}
  }
  console.log(`[ws-relay] CLOSE code=${safeCode} reason=${safeReason}`);
}