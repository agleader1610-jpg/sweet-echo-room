import { createFileRoute } from "@tanstack/react-router";
import { useServerFn } from "@tanstack/react-start";
import { useEffect, useRef, useState } from "react";
import Hls from "hls.js";
import { Maximize, Minimize, Play, Radio, Tv, Film, Share2, Check, Sparkles, Settings, Trophy } from "lucide-react";
import { toast } from "sonner";
import { cn } from "@/lib/utils";
import { getStreamAccess } from "@/lib/stream.functions";
import { WelcomeModal } from "@/components/WelcomeModal";

import brandLogo from "@/assets/brand-logo.png.asset.json";

import btvLogo from "@/assets/channels/btv.png.asset.json";
import somoyLogo from "@/assets/channels/somoy.png.asset.json";
import banglavisionLogo from "@/assets/channels/banglavision.png.asset.json";
import deeptoLogo from "@/assets/channels/deepto.png.asset.json";
import actionLogo from "@/assets/channels/action.png.asset.json";
import channelILogo from "@/assets/channels/channel-i.png.asset.json";
import deshiLogo from "@/assets/channels/deshi-tv.png.asset.json";
import dbcLogo from "@/assets/channels/dbc-news.png.asset.json";
import entBanglaLogo from "@/assets/channels/entertainment-bangla.png.asset.json";
import gseriesLogo from "@/assets/channels/gseries-drama.png.asset.json";

export const Route = createFileRoute("/")({
  head: () => ({
    meta: [
      { title: "FIFA World Cup 2026 Live — Cinema & News Channels" },
      {
        name: "description",
        content:
          "Watch FIFA World Cup 2026 live streams, Bangladeshi news channels and Hollywood cinema — all in one place.",
      },
      { property: "og:title", content: "FIFA World Cup 2026 Live — Cinema & News Channels" },
      {
        property: "og:description",
        content: "Watch FIFA World Cup 2026 live streams, Bangladeshi news channels and Hollywood cinema — all in one place.",
      },
    ],
  }),
  component: Index,
});

type Channel = {
  id: string;
  name: string;
  tagline: string;
  logo: string;
  category: "fifa" | "news" | "cinema";
};

const CHANNELS: Channel[] = [
  
  { id: "btv-fifa", name: "BTV FIFA", tagline: "Bangladesh Television", logo: btvLogo.url, category: "fifa" },
  { id: "somoy", name: "Somoy TV", tagline: "24/7 News · Bangladesh", logo: somoyLogo.url, category: "news" },
  { id: "banglavision", name: "Bangla Vision", tagline: "News & Entertainment", logo: banglavisionLogo.url, category: "news" },
  { id: "deepto", name: "Deepto TV", tagline: "General Entertainment", logo: deeptoLogo.url, category: "news" },
  { id: "channel-i", name: "Channel i", tagline: "News · Bangladesh", logo: channelILogo.url, category: "news" },
  { id: "deshi-tv", name: "Deshi TV", tagline: "News · Bangladesh", logo: deshiLogo.url, category: "news" },
  { id: "dbc-news", name: "DBC News", tagline: "24/7 News · Bangladesh", logo: dbcLogo.url, category: "news" },
  { id: "action-hollywood", name: "Action Hollywood", tagline: "24/7 Movies", logo: actionLogo.url, category: "cinema" },
  { id: "entertainment-bangla", name: "Entertainment Bangla", tagline: "Bangla Movies & Drama", logo: entBanglaLogo.url, category: "cinema" },
  { id: "gseries-drama", name: "GSeries Drama", tagline: "Bangla Drama · 24/7", logo: gseriesLogo.url, category: "cinema" },
];

const CATEGORIES = [
  { id: "all" as const, label: "All Channels", icon: Radio },
  { id: "fifa" as const, label: "FIFA Live", icon: Trophy },
  { id: "news" as const, label: "News", icon: Tv },
  { id: "cinema" as const, label: "Cinema", icon: Film },
];

function Player({ channel, paused }: { channel: Channel; paused: boolean }) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const hlsRef = useRef<Hls | null>(null);
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [levels, setLevels] = useState<{ index: number; label: string }[]>([]);
  const [currentLevel, setCurrentLevel] = useState<number>(-1); // -1 = auto
  const [showQuality, setShowQuality] = useState(false);
  const fetchAccess = useServerFn(getStreamAccess);

  useEffect(() => {
    if (paused) return;
    const video = videoRef.current;
    if (!video) return;
    setError(null);
    setLoading(true);
    setLevels([]);
    setCurrentLevel(-1);


    let hls: Hls | null = null;
    let cancelled = false;
      let retryCount = 0;

    (async () => {
      let signedUrl: string;
      try {
        const res = await fetchAccess({ data: { channelId: channel.id } });
        signedUrl = res.url;
      } catch {
        if (!cancelled) {
          setError("Access token could not be issued.");
          setLoading(false);
        }
        return;
      }
      if (cancelled) return;

      if (Hls.isSupported()) {
        hls = new Hls({ enableWorker: true, lowLatencyMode: true, backBufferLength: 30 });
        hlsRef.current = hls;
        hls.loadSource(signedUrl);
        hls.attachMedia(video);
        hls.on(Hls.Events.MANIFEST_PARSED, (_e, data) => {
          if (cancelled) return;
          const parsed = data.levels.map((lvl, i) => {
            const name = "name" in lvl && typeof lvl.name === "string" ? lvl.name : "";
            const bitrate = typeof lvl.bitrate === "number" ? lvl.bitrate : 0;
            return {
              index: i,
              label: lvl.height ? `${lvl.height}p` : name || (bitrate ? `${Math.round(bitrate / 1000)} kbps` : `Quality ${i + 1}`),
            };
          });
          setLevels(parsed);
          setError(null);
          setLoading(false);
          video.play().catch(() => {});
        });
        hls.on(Hls.Events.LEVEL_SWITCHED, (_e, data) => {
          if (!cancelled && hls?.autoLevelEnabled) setCurrentLevel(-1);
          else if (!cancelled) setCurrentLevel(data.level);
        });
        hls.on(Hls.Events.FRAG_LOADED, () => {
          if (cancelled) return;
          setError(null);
          setLoading(false);
          retryCount = 0;
        });
        hls.on(Hls.Events.FRAG_BUFFERED, () => {
          if (cancelled) return;
          setError(null);
          setLoading(false);
        });
        hls.on(Hls.Events.ERROR, (_e, data) => {
          if (cancelled || !data.fatal) return;

          if (retryCount < 3 && data.type === Hls.ErrorTypes.NETWORK_ERROR) {
            retryCount += 1;
            hls?.startLoad();
            return;
          }

          if (retryCount < 3 && data.type === Hls.ErrorTypes.MEDIA_ERROR) {
            retryCount += 1;
            hls?.recoverMediaError();
            return;
          }

          if (data.details === "bufferAddCodecError") {
            setError(null);
            setLoading(false);
            return;
          }

          setError("Stream could not be loaded. It may be offline right now.");
          setLoading(false);
        });
      } else if (video.canPlayType("application/vnd.apple.mpegurl")) {
        video.src = signedUrl;
        video.addEventListener("loadedmetadata", () => {
          if (cancelled) return;
          setLoading(false);
          video.play().catch(() => {});
        });
      } else {
        setError("Your browser does not support HLS playback.");
        setLoading(false);
      }
    })();

    return () => {
      cancelled = true;
      hls?.destroy();
      hlsRef.current = null;
    };
  }, [channel.id, fetchAccess, paused]);

  useEffect(() => {
    const onFsChange = () => setIsFullscreen(!!document.fullscreenElement);
    document.addEventListener("fullscreenchange", onFsChange);
    return () => document.removeEventListener("fullscreenchange", onFsChange);
  }, []);

  const toggleFullscreen = () => {
    if (!document.fullscreenElement) {
      containerRef.current?.requestFullscreen().catch(() => {});
    } else {
      document.exitFullscreen().catch(() => {});
    }
  };

  const setLevel = (idx: number) => {
    if (hlsRef.current) {
      hlsRef.current.currentLevel = idx; // -1 = auto
      setCurrentLevel(idx);
    }
    setShowQuality(false);
  };

  const currentLabel =
    currentLevel === -1
      ? "Auto"
      : levels.find((l) => l.index === currentLevel)?.label ?? "Manual";


  return (
    <div className="space-y-2">
      <div
        ref={containerRef}
        className="group relative aspect-video w-full overflow-hidden rounded-xl border border-border bg-black shadow-2xl"
      >
        <video
          ref={videoRef}
          controls
          playsInline
          autoPlay
          controlsList="nodownload"
          onContextMenu={(e) => e.preventDefault()}
          className="h-full w-full bg-black"
        />
        {loading && !error && (
          <div className="pointer-events-none absolute inset-0 flex items-center justify-center bg-black/40">
            <div className="flex flex-col items-center gap-3 text-white">
              <div className="h-10 w-10 animate-spin rounded-full border-2 border-white/30 border-t-white" />
              <span className="text-sm font-medium">Loading {channel.name}…</span>
            </div>
          </div>
        )}
        {error && (
          <div className="absolute inset-0 flex items-center justify-center bg-black/80 p-6 text-center">
            <div className="max-w-sm text-white">
              <p className="text-base font-semibold">{channel.name}</p>
              <p className="mt-2 text-sm text-white/70">{error}</p>
            </div>
          </div>
        )}
        <div className="absolute right-3 top-3 z-10 flex items-center gap-2 opacity-90 transition-opacity md:opacity-0 md:group-hover:opacity-100">
          {levels.length > 0 && (
            <div className="relative">
              <button
                onClick={() => setShowQuality((v) => !v)}
                className="flex items-center gap-1.5 rounded-md bg-black/60 px-3 py-1.5 text-xs font-medium text-white backdrop-blur hover:bg-black/80"
              >
                <Settings className="h-3.5 w-3.5" />
                Quality: {currentLabel}
              </button>
              {showQuality && (
                <div className="absolute right-0 top-full mt-1 min-w-[140px] overflow-hidden rounded-md border border-white/10 bg-black/90 py-1 text-xs text-white shadow-xl backdrop-blur">
                  <button
                    onClick={() => setLevel(-1)}
                    className={cn(
                      "flex w-full items-center justify-between px-3 py-1.5 hover:bg-white/10",
                      currentLevel === -1 && "text-emerald-400",
                    )}
                  >
                    Auto {currentLevel === -1 && "✓"}
                  </button>
                  {levels.map((l) => (
                    <button
                      key={l.index}
                      onClick={() => setLevel(l.index)}
                      className={cn(
                        "flex w-full items-center justify-between px-3 py-1.5 hover:bg-white/10",
                        currentLevel === l.index && "text-emerald-400",
                      )}
                    >
                      {l.label} {currentLevel === l.index && "✓"}
                    </button>
                  ))}
                </div>
              )}
            </div>
          )}
          <button
            onClick={toggleFullscreen}
            className="flex items-center gap-1.5 rounded-md bg-black/60 px-3 py-1.5 text-xs font-medium text-white backdrop-blur hover:bg-black/80"
          >
            {isFullscreen ? <Minimize className="h-3.5 w-3.5" /> : <Maximize className="h-3.5 w-3.5" />}
            {isFullscreen ? "Exit" : "Fullscreen"}
          </button>
        </div>
      </div>

    </div>
  );
}

function Index() {
  const [category, setCategory] = useState<"all" | "fifa" | "news" | "cinema">("all");
  const [activeId, setActiveId] = useState<string>(CHANNELS[0].id);
  const [copied, setCopied] = useState(false);
  const [welcomeOpen, setWelcomeOpen] = useState(false);
  const active = CHANNELS.find((c) => c.id === activeId) ?? CHANNELS[0];

  const filtered =
    category === "all" ? CHANNELS : CHANNELS.filter((c) => c.category === category);

  // Deep-link: read ?c=channel-id on mount
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const c = params.get("c");
    if (c && CHANNELS.some((ch) => ch.id === c)) setActiveId(c);
  }, []);

  // Welcome popup: suppress for 10 min after user dismisses (joins Telegram)
  useEffect(() => {
    try {
      const until = Number(localStorage.getItem("welcome_suppress_until") || "0");
      if (Date.now() >= until) setWelcomeOpen(true);
    } catch {
      setWelcomeOpen(true);
    }
  }, []);

  const dismissWelcome = () => {
    try {
      localStorage.setItem(
        "welcome_suppress_until",
        String(Date.now() + 10 * 60 * 1000),
      );
    } catch {
      // ignore
    }
    setWelcomeOpen(false);
  };

  // Keep URL in sync when channel changes (without reload)
  useEffect(() => {
    const url = new URL(window.location.href);
    url.searchParams.set("c", activeId);
    window.history.replaceState(null, "", url.toString());
  }, [activeId]);

  const handleShare = async () => {
    const url = `${window.location.origin}${window.location.pathname}?c=${active.id}`;
    const text = `${active.name} — Live 🔴\n${url}`;
    try {
      if (navigator.share) {
        await navigator.share({ title: active.name, text, url });
        return;
      }
    } catch {
      // fall through to copy
    }
    try {
      await navigator.clipboard.writeText(url);
      setCopied(true);
      toast.success("Link copied!", { description: active.name });
      setTimeout(() => setCopied(false), 2000);
    } catch {
      toast.error("Could not copy link");
    }
  };

  return (
    <div className="flex h-[100dvh] flex-col bg-background text-foreground">
      {welcomeOpen && <WelcomeModal onDismiss={dismissWelcome} />}
      <header className="shrink-0 border-b border-border/60 bg-background/90 backdrop-blur">
        <div className="mx-auto flex max-w-7xl items-center justify-between gap-3 px-3 py-1.5 sm:px-6 sm:py-2">
          <div className="flex min-w-0 items-center gap-2">
            <img
              src={brandLogo.url}
              alt="FIFA World Cup 2026 Live logo"
              className="h-7 w-7 shrink-0 rounded-md shadow ring-1 ring-white/10 sm:h-8 sm:w-8"
            />
            <h1 className="truncate bg-gradient-to-r from-emerald-500 to-cyan-500 bg-clip-text text-sm font-extrabold tracking-tight text-transparent sm:text-base">
              FIFA World Cup 2026 Live
            </h1>
          </div>
          <div className="flex shrink-0 items-center gap-2">
            <span className="hidden items-center gap-1 rounded-full border border-emerald-500/30 bg-gradient-to-r from-emerald-500/15 to-cyan-500/15 px-2 py-0.5 text-[10px] font-bold uppercase tracking-wide text-emerald-600 sm:inline-flex">
              <Sparkles className="h-2.5 w-2.5" />
              HD · Free
            </span>
            <div className="flex items-center gap-1 rounded-full bg-red-500/10 px-2 py-0.5 text-[10px] font-semibold text-red-600">
              <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-red-500" />
              LIVE
            </div>
          </div>
        </div>
      </header>

      <main className="mx-auto flex w-full max-w-7xl flex-1 flex-col overflow-hidden px-3 py-3 sm:px-6 sm:py-4 lg:grid lg:grid-cols-[1fr_320px] lg:gap-6">
        <section className="flex min-h-0 flex-1 flex-col gap-3 lg:overflow-hidden">
          <div className="shrink-0">
            <Player channel={active} paused={welcomeOpen} />
          </div>

          <div className="grid shrink-0 grid-cols-[minmax(0,1fr)_auto] items-center gap-3 rounded-xl border border-border bg-card px-3 py-2">
            <div className="flex min-w-0 items-center gap-2.5">
              <div className="h-9 w-9 shrink-0 overflow-hidden rounded-md border border-border bg-white">
                <img src={active.logo} alt={active.name} className="h-full w-full object-contain p-0.5" />
              </div>
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-1.5">
                  <h2 className="truncate text-sm font-bold sm:text-base">{active.name}</h2>
                  <span className="rounded-full bg-red-500/10 px-1.5 py-0.5 text-[9px] font-semibold uppercase text-red-600">
                    Live
                  </span>
                </div>
                <p className="truncate text-[11px] text-muted-foreground">{active.tagline}</p>
              </div>
              <button
                onClick={handleShare}
                title="Share this channel"
                aria-label="Share this channel"
                className="ml-1 inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-full border border-border bg-background text-muted-foreground transition-colors hover:border-primary hover:text-primary"
              >
                {copied ? <Check className="h-4 w-4 text-emerald-500" /> : <Share2 className="h-4 w-4" />}
              </button>
            </div>
            <div className="flex shrink-0 items-center gap-1 overflow-x-auto">

              {CATEGORIES.map((c) => {
                const Icon = c.icon;
                const activeTab = c.id === category;
                return (
                  <button
                    key={c.id}
                    onClick={() => setCategory(c.id)}
                    title={c.label}
                    className={cn(
                      "inline-flex shrink-0 items-center gap-1 rounded-full border px-2 py-1 text-[11px] font-medium transition-colors sm:px-2.5",
                      activeTab
                        ? "border-primary bg-primary text-primary-foreground"
                        : "border-border bg-card text-muted-foreground hover:text-foreground",
                    )}
                  >
                    <Icon className="h-3.5 w-3.5" />
                    <span className="hidden sm:inline">{c.label}</span>
                  </button>
                );
              })}
            </div>
          </div>

          <div className="min-h-0 flex-1 overflow-y-auto lg:hidden">
            <div className="grid grid-cols-2 gap-2 pb-2 sm:grid-cols-3">
              {filtered.map((ch) => (
                <ChannelCard
                  key={ch.id}
                  channel={ch}
                  active={ch.id === activeId}
                  onSelect={() => setActiveId(ch.id)}
                />
              ))}
            </div>
          </div>
        </section>

        <aside className="hidden min-h-0 flex-col lg:flex lg:overflow-hidden">
          <h3 className="shrink-0 px-1 pb-2 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
            Channels ({filtered.length})
          </h3>
          <div className="min-h-0 flex-1 space-y-2 overflow-y-auto pr-1">
            {filtered.map((ch) => (
              <ChannelRow
                key={ch.id}
                channel={ch}
                active={ch.id === activeId}
                onSelect={() => setActiveId(ch.id)}
              />
            ))}
          </div>
        </aside>
      </main>
    </div>
  );
}

function ChannelRow({
  channel,
  active,
  onSelect,
}: {
  channel: Channel;
  active: boolean;
  onSelect: () => void;
}) {
  return (
    <button
      onClick={onSelect}
      className={cn(
        "flex w-full items-center gap-3 rounded-lg border p-2.5 text-left transition-all",
        active
          ? "border-primary bg-primary/5 shadow-sm"
          : "border-border bg-card hover:border-primary/40 hover:bg-accent",
      )}
    >
      <div className="h-11 w-11 shrink-0 overflow-hidden rounded-md border border-border bg-white">
        <img src={channel.logo} alt={channel.name} loading="lazy" className="h-full w-full object-contain p-1" />
      </div>
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-semibold">{channel.name}</p>
        <p className="truncate text-[11px] text-muted-foreground">{channel.tagline}</p>
      </div>
      {active ? (
        <span className="flex items-center gap-1 rounded-full bg-red-500/10 px-1.5 py-0.5 text-[9px] font-bold text-red-600">
          <span className="h-1 w-1 animate-pulse rounded-full bg-red-500" />
          ON
        </span>
      ) : (
        <Play className="h-4 w-4 text-muted-foreground" />
      )}
    </button>
  );
}

function ChannelCard({
  channel,
  active,
  onSelect,
}: {
  channel: Channel;
  active: boolean;
  onSelect: () => void;
}) {
  return (
    <button
      onClick={onSelect}
      className={cn(
        "flex flex-col items-center gap-2 rounded-lg border p-3 text-center transition-all",
        active ? "border-primary bg-primary/5" : "border-border bg-card hover:border-primary/40",
      )}
    >
      <div className="h-14 w-14 overflow-hidden rounded-md border border-border bg-white">
        <img src={channel.logo} alt={channel.name} loading="lazy" className="h-full w-full object-contain p-1" />
      </div>
      <div className="min-w-0">
        <p className="truncate text-xs font-semibold">{channel.name}</p>
        <p className="truncate text-[10px] text-muted-foreground">{channel.tagline}</p>
      </div>
    </button>
  );
}
