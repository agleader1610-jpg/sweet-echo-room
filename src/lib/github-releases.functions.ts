import { createServerFn } from '@tanstack/react-start';
import { fetchBuildsFromGithub, type Build } from '@/lib/github-releases';

export type { Build };

export const listBuilds = createServerFn({ method: 'GET' }).handler(async () => {
  try {
    return { ok: true as const, builds: await fetchBuildsFromGithub() };
  } catch (e) {
    console.error('[listBuilds] failed', e);
    return { ok: false as const, builds: [] as Build[] };
  }
});
