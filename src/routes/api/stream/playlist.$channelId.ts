import { createFileRoute } from "@tanstack/react-router";
import {
  SERVER_CHANNELS,
  fetchUpstream,
  rewritePlaylist,
  verifyChannelToken,
} from "@/lib/channels.server";

export const Route = createFileRoute("/api/stream/playlist/$channelId")({
  server: {
    handlers: {
      GET: async ({ request, params }) => {
        try {
          const channel = SERVER_CHANNELS[params.channelId];
          if (!channel) return new Response("Not found", { status: 404 });

          const token = new URL(request.url).searchParams.get("t") ?? "";
          if (!verifyChannelToken(channel.id, token)) {
            return new Response("Forbidden", { status: 403 });
          }

          const upstream = await fetchUpstream(channel.url, channel.headers);
          if (!upstream.ok) {
            console.error("playlist upstream not ok", params.channelId, upstream.status);
            return new Response(`Upstream ${upstream.status}`, { status: 502 });
          }
          const text = await upstream.text();
          let rewritten: string;
          try {
            rewritten = rewritePlaylist(text, channel.url);
          } catch (err) {
            console.error("playlist rewrite failed", params.channelId, err);
            throw err;
          }
          return new Response(rewritten, {
            status: 200,
            headers: {
              "Content-Type": "application/vnd.apple.mpegurl",
              "Cache-Control": "no-store",
            },
          });
        } catch (err) {
          console.error("playlist handler crashed", params.channelId, err);
          return new Response("Handler error", { status: 500 });
        }
      },
    },
  },
});
