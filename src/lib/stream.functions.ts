import { createServerFn } from "@tanstack/react-start";

export const getStreamAccess = createServerFn({ method: "GET" })
  .inputValidator((data: { channelId: string }) => data)
  .handler(async ({ data }) => {
    const { SERVER_CHANNELS, issueChannelToken } = await import("./channels.server");
    const channel = SERVER_CHANNELS[data.channelId];
    if (!channel) throw new Error("Unknown channel");
    if (channel.direct) {
      return { url: channel.url };
    }
    const { token } = issueChannelToken(channel.id);
    return {
      url: `/api/stream/playlist/${channel.id}?t=${token}`,
    };
  });
