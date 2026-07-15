import { createFileRoute } from "@tanstack/react-router";
import { decryptSegmentUrl, fetchUpstream, rewritePlaylist } from "@/lib/channels.server";

export const Route = createFileRoute("/api/stream/proxy")({
  server: {
    handlers: {
      GET: async ({ request }) => {
        const url = new URL(request.url);
        const sealed = url.searchParams.get("s");
        if (!sealed) return new Response("Bad request", { status: 400 });
        const target = decryptSegmentUrl(sealed);
        if (!target) return new Response("Forbidden", { status: 403 });

        const range = request.headers.get("range") ?? undefined;
        const upstream = await fetchUpstream(target, range ? { Range: range } : undefined);
        if (!upstream.ok && upstream.status !== 206) {
          return new Response(`Upstream ${upstream.status}`, { status: 502 });
        }

        const contentType =
          upstream.headers.get("content-type") ?? "application/octet-stream";

        // If this is a nested playlist (variant), rewrite it too.
        if (
          contentType.includes("mpegurl") ||
          target.endsWith(".m3u8") ||
          target.includes(".m3u8?")
        ) {
          const text = await upstream.text();
          const rewritten = rewritePlaylist(text, target);
          return new Response(rewritten, {
            status: 200,
            headers: {
              "Content-Type": "application/vnd.apple.mpegurl",
              "Cache-Control": "no-store",
            },
          });
        }

        // Binary passthrough (segments, keys, init fragments)
        const headers = new Headers();
        headers.set("Content-Type", contentType);
        const len = upstream.headers.get("content-length");
        if (len) headers.set("Content-Length", len);
        const cr = upstream.headers.get("content-range");
        if (cr) headers.set("Content-Range", cr);
        const ar = upstream.headers.get("accept-ranges");
        if (ar) headers.set("Accept-Ranges", ar);
        headers.set("Cache-Control", "no-store");

        return new Response(upstream.body, { status: upstream.status, headers });
      },
    },
  },
});
