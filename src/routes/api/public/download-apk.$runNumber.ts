import { createFileRoute } from '@tanstack/react-router';
import { fetchBuildsFromGithub, getGithubGatewayConfig } from '@/lib/github-releases';

export const Route = createFileRoute('/api/public/download-apk/$runNumber')({
  server: {
    handlers: {
      GET: async ({ params }) => {
        try {
          const runNumber = Number(params.runNumber);
          if (!Number.isFinite(runNumber)) return text('Invalid build number', 400);

          const build = (await fetchBuildsFromGithub()).find((b) => b.runNumber === runNumber);
          if (!build) return text('APK build not found', 404);

          const lovableKey = process.env.LOVABLE_API_KEY;
          const ghKey = process.env.GITHUB_API_KEY;
          if (!lovableKey || !ghKey) return text('Download is not configured', 503);

          const { repo, gateway } = getGithubGatewayConfig();
          const res = await fetch(`${gateway}/repos/${repo}/releases/assets/${build.assetId}`, {
            headers: {
              Accept: 'application/octet-stream',
              Authorization: `Bearer ${lovableKey}`,
              'X-Connection-Api-Key': ghKey,
            },
          });

          if (!res.ok || !res.body) return text('APK download failed', 502);

          return new Response(res.body, {
            status: 200,
            headers: {
              'Access-Control-Allow-Origin': '*',
              'Cache-Control': 'no-store',
              'Content-Type': 'application/vnd.android.package-archive',
              'Content-Disposition': `attachment; filename="${safeFileName(build.assetName)}"`,
            },
          });
        } catch {
          return text('APK download failed', 500);
        }
      },
    },
  },
});

function safeFileName(name: string): string {
  return name.replace(/[^a-zA-Z0-9._-]/g, '_') || 'APKBid.apk';
}

function text(body: string, status: number): Response {
  return new Response(body, {
    status,
    headers: {
      'Access-Control-Allow-Origin': '*',
      'Cache-Control': 'no-store',
      'Content-Type': 'text/plain; charset=utf-8',
    },
  });
}