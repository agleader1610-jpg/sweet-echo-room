// SERVER-ONLY. Never import this from client/component code.
// Real upstream stream URLs live here so they are never shipped to the browser bundle.

export type ServerChannel = {
  id: string;
  name: string;
  tagline: string;
  category: "fifa" | "news" | "cinema";
  url: string;
  // Some upstreams block unknown UAs / require a referer
  headers?: Record<string, string>;
  // When true, ship the upstream URL directly to the client (no proxy).
  // Use for geo-blocked origins that reject Cloudflare edge IPs.
  direct?: boolean;
};

export const SERVER_CHANNELS: Record<string, ServerChannel> = {
  "btv-fifa": {
    id: "btv-fifa",
    name: "BTV FIFA",
    tagline: "Bangladesh Television",
    category: "fifa",
    url: "http://180.94.28.28:8097//BTV/index.fmp4.m3u8",
  },
  somoy: {
    id: "somoy",
    name: "Somoy TV",
    tagline: "24/7 News · Bangladesh",
    category: "news",
    url: "https://live.thebosstv.com:30443/dwlive/Somoy-TV/playlist.m3u8",
  },
  banglavision: {
    id: "banglavision",
    name: "Bangla Vision",
    tagline: "News & Entertainment",
    category: "news",
    url: "http://103.175.242.10:8080/banglavision/index.m3u8",
    direct: true,
  },
  deepto: {
    id: "deepto",
    name: "Deepto TV",
    tagline: "General Entertainment",
    category: "news",
    url: "https://byphdgllyk.gpcdn.net/hls/deeptotv/0_1/index.m3u8",
  },
  "channel-i": {
    id: "channel-i",
    name: "Channel i",
    tagline: "News · Bangladesh",
    category: "news",
    url: "https://owrcovcrpy.gpcdn.net/bpk-tv/1702/output/1702-audio_113322_eng=113200-video=2202800.m3u8",
  },
  "deshi-tv": {
    id: "deshi-tv",
    name: "Deshi TV",
    tagline: "News · Bangladesh",
    category: "news",
    url: "https://deshitv.deshitv24.net/live/myStream/playlist.m3u8",
  },
  "dbc-news": {
    id: "dbc-news",
    name: "DBC News",
    tagline: "24/7 News · Bangladesh",
    category: "news",
    url: "https://owrcovcrpy.gpcdn.net/bpk-tv/1728/output/index.m3u8",
  },
  "action-hollywood": {
    id: "action-hollywood",
    name: "Action Hollywood",
    tagline: "24/7 Movies",
    category: "cinema",
    url: "https://amg01076-lightningintern-actionhollywood-samsungnz-82rry.amagi.tv/playlist/amg01076-lightningintern-actionhollywood-samsungnz/playlist.m3u8",
  },
  "entertainment-bangla": {
    id: "entertainment-bangla",
    name: "Entertainment Bangla",
    tagline: "Bangla Movies & Drama",
    category: "cinema",
    url: "https://live-bangla.akamaized.net/liveabr/pub-iobanglakp3sff/live_720p/chunks.m3u8",
  },
  "gseries-drama": {
    id: "gseries-drama",
    name: "GSeries Drama",
    tagline: "Bangla Drama · 24/7",
    category: "cinema",
    url: "https://vods2.aynaott.com/gseriesDrama/tracks-v1a1/mono.ts.m3u8",
  },
};

// ---- HMAC signing helpers ----------------------------------------------------

import { createHmac, timingSafeEqual, createCipheriv, createDecipheriv, randomBytes, createHash } from "node:crypto";
import { request as httpsRequest } from "node:https";
import { createConnection } from "node:net";

const TOKEN_TTL_SECONDS = 60 * 60 * 6; // 6 hours

function secret(): string {
  const s = process.env.STREAM_SECRET;
  if (!s) throw new Error("STREAM_SECRET is not configured");
  return s;
}

function sign(payload: string): string {
  return createHmac("sha256", secret()).update(payload).digest("base64url");
}

export function issueChannelToken(channelId: string): { token: string; exp: number } {
  const exp = Math.floor(Date.now() / 1000) + TOKEN_TTL_SECONDS;
  const sig = sign(`ch:${channelId}:${exp}`);
  return { token: `${exp}.${sig}`, exp };
}

export function verifyChannelToken(channelId: string, token: string): boolean {
  const [expStr, sig] = token.split(".");
  if (!expStr || !sig) return false;
  const exp = Number(expStr);
  if (!Number.isFinite(exp) || exp < Math.floor(Date.now() / 1000)) return false;
  const expected = sign(`ch:${channelId}:${exp}`);
  const a = Buffer.from(sig);
  const b = Buffer.from(expected);
  return a.length === b.length && timingSafeEqual(a, b);
}

function encKey(): Buffer {
  return createHash("sha256").update(secret()).digest();
}

// Opaque, encrypted segment token — hides the upstream URL from the browser.
// Format (base64url): iv(12) | tag(16) | exp(uint32 BE, 4) | ciphertext(url)
export function encryptSegmentUrl(url: string): string {
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", encKey(), iv);
  const enc = Buffer.concat([cipher.update(url, "utf8"), cipher.final()]);
  const tag = cipher.getAuthTag();
  const expBuf = Buffer.alloc(4);
  expBuf.writeUInt32BE(Math.floor(Date.now() / 1000) + TOKEN_TTL_SECONDS, 0);
  return Buffer.concat([iv, tag, expBuf, enc]).toString("base64url");
}

export function decryptSegmentUrl(token: string): string | null {
  try {
    const buf = Buffer.from(token, "base64url");
    if (buf.length < 12 + 16 + 4 + 1) return null;
    const iv = buf.subarray(0, 12);
    const tag = buf.subarray(12, 28);
    const exp = buf.readUInt32BE(28);
    if (exp < Math.floor(Date.now() / 1000)) return null;
    const enc = buf.subarray(32);
    const decipher = createDecipheriv("aes-256-gcm", encKey(), iv);
    decipher.setAuthTag(tag);
    const dec = Buffer.concat([decipher.update(enc), decipher.final()]);
    return dec.toString("utf8");
  } catch {
    return null;
  }
}

// ---- m3u8 rewriter -----------------------------------------------------------

const UPSTREAM_HEADERS = {
  "User-Agent":
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36",
  Accept: "*/*",
};

// Cloudflare Workers' fetch() only allows a small set of outbound ports
// (80, 443, 8080, 8443, 2052/53, 2082/83, 2086/87, 2095/96). Some upstream
// IPTV origins publish on non-standard ports (e.g. :8097). For those we
// speak raw HTTP over a TCP socket via cloudflare:sockets, which has no
// port restriction. This is used only when needed.
const ALLOWED_FETCH_PORTS = new Set([
  80, 443, 8080, 8443, 2052, 2053, 2082, 2083, 2086, 2087, 2095, 2096,
]);

function needsSocketPath(u: URL): boolean {
  const port = u.port ? Number(u.port) : u.protocol === "https:" ? 443 : 80;
  return !ALLOWED_FETCH_PORTS.has(port);
}

function findDoubleCrlf(buf: Uint8Array): number {
  for (let i = 0; i < buf.length - 3; i++) {
    if (buf[i] === 13 && buf[i + 1] === 10 && buf[i + 2] === 13 && buf[i + 3] === 10) return i;
  }
  return -1;
}

function dechunk(body: Uint8Array): Uint8Array {
  const out: number[] = [];
  let i = 0;
  const dec = new TextDecoder();
  while (i < body.length) {
    let lineEnd = i;
    while (lineEnd < body.length - 1 && !(body[lineEnd] === 13 && body[lineEnd + 1] === 10)) lineEnd++;
    const sizeHex = dec.decode(body.subarray(i, lineEnd)).split(";")[0].trim();
    const size = parseInt(sizeHex, 16);
    if (!Number.isFinite(size)) break;
    i = lineEnd + 2;
    if (size === 0) break;
    for (let k = 0; k < size && i < body.length; k++) out.push(body[i++]);
    i += 2; // trailing CRLF
  }
  return new Uint8Array(out);
}

function responseFromRawHttp(chunks: Uint8Array[]): Response {
  let total = 0;
  for (const c of chunks) total += c.length;
  const buf = new Uint8Array(total);
  let off = 0;
  for (const c of chunks) { buf.set(c, off); off += c.length; }

  const sep = findDoubleCrlf(buf);
  if (sep < 0) return new Response("Bad upstream", { status: 502 });
  const headerText = new TextDecoder().decode(buf.subarray(0, sep));
  let body = buf.subarray(sep + 4);
  const lines = headerText.split("\r\n");
  const statusParts = lines[0].split(" ");
  const status = parseInt(statusParts[1] ?? "0", 10) || 502;
  const respHeaders = new Headers();
  for (let i = 1; i < lines.length; i++) {
    const idx = lines[i].indexOf(":");
    if (idx > 0) respHeaders.set(lines[i].slice(0, idx).trim(), lines[i].slice(idx + 1).trim());
  }
  const te = respHeaders.get("transfer-encoding");
  if (te && te.toLowerCase().includes("chunked")) {
    body = dechunk(body) as Uint8Array<ArrayBuffer>;
    respHeaders.delete("transfer-encoding");
    respHeaders.set("content-length", String(body.length));
  }
  return new Response(body, { status, headers: respHeaders });
}

async function fetchOverHttpsRequest(
  urlStr: string,
  extraHeaders?: Record<string, string>,
): Promise<Response> {
  const u = new URL(urlStr);
  const headers: Record<string, string> = {
    ...UPSTREAM_HEADERS,
    "Accept-Encoding": "identity",
    Connection: "close",
    ...(extraHeaders ?? {}),
  };

  return new Promise((resolve, reject) => {
    const req = httpsRequest(
      {
        protocol: u.protocol,
        hostname: u.hostname,
        port: u.port ? Number(u.port) : 443,
        path: `${u.pathname}${u.search}`,
        method: "GET",
        headers,
        timeout: 15_000,
      },
      (res) => {
        const chunks: Uint8Array[] = [];
        res.on("data", (chunk: Uint8Array) => chunks.push(chunk));
        res.on("end", () => {
          let total = 0;
          for (const c of chunks) total += c.length;
          const body = new Uint8Array(total);
          let offset = 0;
          for (const c of chunks) {
            body.set(c, offset);
            offset += c.length;
          }
          const respHeaders = new Headers();
          for (const [key, value] of Object.entries(res.headers)) {
            if (Array.isArray(value)) respHeaders.set(key, value.join(", "));
            else if (value !== undefined) respHeaders.set(key, String(value));
          }
          resolve(new Response(body, { status: res.statusCode ?? 502, headers: respHeaders }));
        });
      },
    );
    req.on("timeout", () => req.destroy(new Error("Upstream HTTPS timeout")));
    req.on("error", reject);
    req.end();
  });
}

async function fetchOverPlainSocket(
  urlStr: string,
  extraHeaders?: Record<string, string>,
): Promise<Response> {
  const u = new URL(urlStr);
  const port = u.port ? Number(u.port) : u.protocol === "https:" ? 443 : 80;
  const enc = new TextEncoder();
  const headers: Record<string, string> = {
    Host: u.host,
    ...UPSTREAM_HEADERS,
    "Accept-Encoding": "identity",
    Connection: "close",
    ...(extraHeaders ?? {}),
  };
  let req = `GET ${u.pathname}${u.search} HTTP/1.1\r\n`;
  for (const [k, v] of Object.entries(headers)) req += `${k}: ${v}\r\n`;
  req += "\r\n";

  const chunks = await new Promise<Uint8Array[]>((resolve, reject) => {
    const socket = createConnection({ host: u.hostname, port }, () => {
      socket.write(enc.encode(req));
    });
    const parts: Uint8Array[] = [];
    socket.setTimeout(15_000, () => socket.destroy(new Error("Upstream socket timeout")));
    socket.on("data", (chunk: Uint8Array) => parts.push(chunk));
    socket.on("end", () => resolve(parts));
    socket.on("error", reject);
  });
  return responseFromRawHttp(chunks);
}

export async function fetchUpstream(url: string, extraHeaders?: Record<string, string>) {
  const u = new URL(url);
  try {
    if (needsSocketPath(u)) {
      return u.protocol === "https:"
        ? await fetchOverHttpsRequest(url, extraHeaders)
        : await fetchOverPlainSocket(url, extraHeaders);
    }
    return await fetch(url, {
      headers: { ...UPSTREAM_HEADERS, ...(extraHeaders ?? {}) },
      redirect: "follow",
    });
  } catch (error) {
    console.error("Upstream fetch failed", url, error);
    return new Response("Upstream unavailable", { status: 502 });
  }
}

function findFirstVariantUri(content: string): string | null {
  const lines = content.split(/\r?\n/);
  for (let i = 0; i < lines.length; i++) {
    if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue;
    for (let j = i + 1; j < lines.length; j++) {
      const candidate = lines[j].trim();
      if (!candidate || candidate.startsWith("#")) continue;
      return candidate;
    }
  }
  return null;
}

export async function makePlayablePlaylist(
  content: string,
  playlistUrl: string,
  extraHeaders?: Record<string, string>,
): Promise<string> {
  const variantUri = findFirstVariantUri(content);
  if (!variantUri) return rewritePlaylist(content, playlistUrl);

  const variantUrl = new URL(variantUri, playlistUrl).toString();
  const variant = await fetchUpstream(variantUrl, extraHeaders);
  if (!variant.ok) return rewritePlaylist(content, playlistUrl);

  const variantText = await variant.text();
  return rewritePlaylist(variantText, variantUrl);
}

export function rewritePlaylist(content: string, playlistUrl: string): string {
  const base = new URL(playlistUrl);
  const proxyFor = (u: string) => {
    const abs = new URL(u, base).toString();
    return `/api/stream/proxy?s=${encryptSegmentUrl(abs)}`;
  };
  return content
    .split(/\r?\n/)
    .map((line) => {
      if (!line) return line;
      if (line.startsWith("#")) {
        // Rewrite URI="..." attributes on tags such as EXT-X-KEY, EXT-X-MAP, EXT-X-MEDIA
        return line.replace(/URI="([^"]+)"/g, (_m, u) => `URI="${proxyFor(u)}"`);
      }
      return proxyFor(line);
    })
    .join("\n");
}
