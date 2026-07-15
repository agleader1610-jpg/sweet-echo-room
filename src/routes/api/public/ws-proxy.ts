import { createFileRoute } from '@tanstack/react-router';

const BUILT_IN_ANDROID_RELAY_KEY = 'ns-android-2026-relay-4d184ca9-2578-4035';

/**
 * WebSocket relay proxy.
 *
 * Usage from the Android APK:
 *   const ws = new WebSocket(`wss://tv.eip.lat/api/public/ws-proxy?k=...&target=${encodeURIComponent('wss://echo.websocket.events/')}`)
 *
 * Every frame is logged server-side (visible via server-function-logs),
 * so nothing is missed even if the on-device interception fails.
 * WS_RELAY_TOKEN remains supported for private/manual relay clients.
 *
 * Runs on Cloudflare Workers using the standard WebSocketPair upgrade.
 */
export const Route = createFileRoute('/api/public/ws-proxy')({
  server: {
    handlers: {
      GET: async ({ request }) => {
        const upgrade = request.headers.get('upgrade');
        if (!upgrade || upgrade.toLowerCase() !== 'websocket') {
          return new Response('Expected websocket upgrade', { status: 426 });
        }

        const url = new URL(request.url);
        if (!isAuthorizedRelayRequest(url, request)) {
          return new Response('Unauthorized', { status: 401 });
        }

        const rawTarget = url.searchParams.get('target');
        if (!rawTarget) {
          return new Response('Missing ?target=wss://...', { status: 400 });
        }

        let target: URL;
        try {
          target = new URL(rawTarget);
        } catch {
          return new Response('Invalid target URL', { status: 400 });
        }
        if (target.protocol !== 'ws:' && target.protocol !== 'wss:') {
          return new Response('target must be ws:// or wss://', { status: 400 });
        }
        if (target.username || target.password || isBlockedHost(target.hostname)) {
          return new Response('target is not allowed', { status: 400 });
        }
        if (isSelfRelayTarget(target, request)) {
          return new Response('target is not allowed', { status: 400 });
        }
        const allowedHosts = (process.env.WS_RELAY_ALLOWED_HOSTS ?? '')
          .split(',')
          .map((host) => host.trim().toLowerCase())
          .filter(Boolean);
        if (allowedHosts.length > 0 && !allowedHosts.includes(target.hostname.toLowerCase())) {
          return new Response('target host is not allowed', { status: 403 });
        }

        // Open the upstream WebSocket via fetch-upgrade (Cloudflare Worker idiom).
        const upstreamFetchUrl =
          (target.protocol === 'wss:' ? 'https://' : 'http://') +
          target.host +
          target.pathname +
          target.search;

        const upstreamHeaders = new Headers({ Upgrade: 'websocket' });
        const protocolHeader = request.headers.get('sec-websocket-protocol');
        const originHeader = request.headers.get('origin');
        const userAgentHeader = request.headers.get('user-agent');
        const languageHeader = request.headers.get('accept-language');
        const targetCookie = url.searchParams.get('target_cookie');
        if (protocolHeader) upstreamHeaders.set('Sec-WebSocket-Protocol', protocolHeader);
        if (originHeader) upstreamHeaders.set('Origin', originHeader);
        if (userAgentHeader) upstreamHeaders.set('User-Agent', userAgentHeader);
        if (languageHeader) upstreamHeaders.set('Accept-Language', languageHeader);
        if (targetCookie) upstreamHeaders.set('Cookie', targetCookie);

        let upstreamResp: Response;
        try {
          upstreamResp = await fetch(upstreamFetchUrl, {
            headers: upstreamHeaders,
          });
        } catch (err) {
          return new Response(`Upstream connect failed: ${String(err)}`, { status: 502 });
        }

        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        const upstream: WebSocket | null = (upstreamResp as any).webSocket ?? null;
        if (!upstream || upstreamResp.status !== 101) {
          return new Response(
            `Upstream did not upgrade (status=${upstreamResp.status})`,
            { status: 502 },
          );
        }

        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        const pair = new (globalThis as any).WebSocketPair();
        const client: WebSocket = pair[0];
        const server: WebSocket = pair[1];

        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        (server as any).accept();
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        (upstream as any).accept();

        const targetLabel = target.toString();
        const preview = (data: unknown): string => {
          if (typeof data === 'string') {
            return data.length > 512 ? data.slice(0, 512) + `… (+${data.length - 512} chars)` : data;
          }
          if (data instanceof ArrayBuffer) {
            return `[binary ${data.byteLength} bytes]`;
          }
          return `[${Object.prototype.toString.call(data)}]`;
        };

        console.log(`[ws-proxy] OPEN → ${targetLabel}`);

        server.addEventListener('message', (ev: MessageEvent) => {
          console.log(`[ws-proxy] c→s ${targetLabel} ${preview(ev.data)}`);
          try {
            upstream.send(ev.data as string | ArrayBuffer);
          } catch (err) {
            console.error(`[ws-proxy] forward c→s failed: ${String(err)}`);
          }
        });
        upstream.addEventListener('message', (ev: MessageEvent) => {
          console.log(`[ws-proxy] s→c ${targetLabel} ${preview(ev.data)}`);
          try {
            server.send(ev.data as string | ArrayBuffer);
          } catch (err) {
            console.error(`[ws-proxy] forward s→c failed: ${String(err)}`);
          }
        });

        const closePair = (code: number, reason: string, origin: string) => {
          console.log(`[ws-proxy] CLOSE (${origin}) code=${code} reason=${reason}`);
          try { server.close(code, reason); } catch { /* noop */ }
          try { upstream.close(code, reason); } catch { /* noop */ }
        };

        server.addEventListener('close', (ev: CloseEvent) => closePair(ev.code || 1000, ev.reason || '', 'client'));
        upstream.addEventListener('close', (ev: CloseEvent) => closePair(ev.code || 1000, ev.reason || '', 'upstream'));
        server.addEventListener('error', () => closePair(1011, 'client error', 'client'));
        upstream.addEventListener('error', () => closePair(1011, 'upstream error', 'upstream'));

        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        const responseHeaders = new Headers();
        const acceptedProtocol = upstreamResp.headers.get('sec-websocket-protocol');
        if (acceptedProtocol) responseHeaders.set('Sec-WebSocket-Protocol', acceptedProtocol);

        return new Response(null, { status: 101, webSocket: client, headers: responseHeaders } as any);
      },
    },
  },
});

function isAuthorizedRelayRequest(url: URL, request: Request): boolean {
  const relayToken = process.env.WS_RELAY_TOKEN;
  const suppliedToken = url.searchParams.get('token') ?? request.headers.get('x-relay-token') ?? '';
  if (relayToken && safeEqual(suppliedToken, relayToken)) return true;

  const suppliedClientKey = url.searchParams.get('k') ?? request.headers.get('x-relay-client-key') ?? '';
  return safeEqual(suppliedClientKey, BUILT_IN_ANDROID_RELAY_KEY);
}

function safeEqual(a: string, b: string): boolean {
  const enc = new TextEncoder();
  const left = enc.encode(a);
  const right = enc.encode(b);
  if (left.length !== right.length) return false;
  let diff = 0;
  for (let i = 0; i < left.length; i += 1) diff |= left[i] ^ right[i];
  return diff === 0;
}

function isSelfRelayTarget(target: URL, request: Request): boolean {
  try {
    const self = new URL(request.url);
    return target.hostname.toLowerCase() === self.hostname.toLowerCase()
      && (target.pathname.includes('/api/public/ws-proxy') || target.pathname.includes('/ws-proxy'));
  } catch {
    return false;
  }
}

function isBlockedHost(hostname: string): boolean {
  const host = hostname.toLowerCase();
  if (host === 'localhost' || host.endsWith('.localhost')) return true;
  if (/^(0|10|127)\./.test(host)) return true;
  if (/^169\.254\./.test(host)) return true;
  if (/^192\.168\./.test(host)) return true;
  if (/^172\.(1[6-9]|2\d|3[0-1])\./.test(host)) return true;
  if (host === '::1' || host.startsWith('fc') || host.startsWith('fd') || host.startsWith('fe80')) return true;
  return false;
}
