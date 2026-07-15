// Plain helper (NOT a .functions.ts file) so the TanStack server-fn splitter
// doesn't strip it as a "sibling runtime declaration" of a serverFn.
// Fetches recent Android build releases from GitHub via the Lovable connector.

const REPO = 'mdsamratsha98-ctrl/apk-build';
const GATEWAY = 'https://connector-gateway.lovable.dev/github';

export type Build = {
  runNumber: number;
  tag: string;
  apkUrl: string;
  assetId: number;
  assetName: string;
  publishedAt: string;
  name: string;
};

export function getGithubGatewayConfig() {
  return { repo: REPO, gateway: GATEWAY };
}

export async function fetchBuildsFromGithub(): Promise<Build[]> {
  const lovableKey = process.env.LOVABLE_API_KEY;
  const ghKey = process.env.GITHUB_API_KEY;
  if (!lovableKey || !ghKey) {
    console.warn('[github-releases] missing keys', {
      hasLovable: !!lovableKey,
      hasGithub: !!ghKey,
    });
    return [];
  }
  const res = await fetch(
    `${GATEWAY}/repos/${REPO}/releases?per_page=20`,
    {
      headers: {
        Accept: 'application/vnd.github+json',
        Authorization: `Bearer ${lovableKey}`,
        'X-Connection-Api-Key': ghKey,
      },
    },
  );
  if (!res.ok) {
    console.error('[github-releases] gateway error', res.status, await res.text());
    return [];
  }
  const releases = (await res.json()) as any[];
  const out: Build[] = [];
  for (const r of releases) {
    const m = String(r.tag_name ?? '').match(/android-build-(\d+)/);
    if (!m) continue;
    const asset = (r.assets ?? []).find((a: any) =>
      String(a.name ?? '').endsWith('.apk'),
    );
    if (!asset) continue;
    out.push({
      runNumber: Number(m[1]),
      tag: r.tag_name,
      apkUrl: asset.browser_download_url,
      assetId: Number(asset.id),
      assetName: String(asset.name ?? `APKBid-${m[1]}.apk`),
      publishedAt: r.published_at ?? '',
      name: r.name ?? r.tag_name,
    });
  }
  out.sort((a, b) => b.runNumber - a.runNumber);
  return out;
}
