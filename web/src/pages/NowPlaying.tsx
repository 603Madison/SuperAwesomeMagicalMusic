import { useEffect, useRef, useState, type CSSProperties } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { Info, Maximize2, Minimize2, Pause, Play, SkipBack, SkipForward } from "lucide-react";
import { api, art } from "../lib/api";
import { duration } from "../lib/format";
import type { LyricsView, NowView, Track } from "../lib/types";
import { player, usePlayer } from "../player/player";
import { useSeek } from "../player/useSeek";
import { Artwork } from "../components/Artwork";
import { albumNamed, artistNamed } from "../components/Cards";
import { Queue } from "../components/Queue";
import { favoriteOf, FavoriteButton } from "../components/Favorite";
import { splitTwo } from "../player/live";
import { TrackMenu } from "../components/TrackList";
import { Button, IconButton, Spinner } from "../components/ui";
import { TrackInfo } from "../components/TrackInfo";
import { StationThumbs } from "../components/Thumbs";

/**
 * What's playing, made to be looked at: the cover large and still, the room washed in its
 * colours, and the lyrics beside it following the song (tap a line to go there).
 */
export function NowPlaying() {
  const item = usePlayer((s) => s.queue[s.index]);
  const current = usePlayer((s) => s.current);
  const buffering = usePlayer((s) => s.buffering);
  const playing = usePlayer((s) => s.playing);
  const liveNow = usePlayer((s) => s.liveNow);
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  // Wide windows show the cover beside the lyrics or queue; narrow ones show one at a time.
  const narrow = useMedia("(max-width: 1000px)");
  // The tab last chosen is kept: someone who'd rather not see lyrics gets the song.
  const asked = params.get("view") ?? rememberedView();
  const view = asked === "queue" || asked === "lyrics" || asked === "cover" ? asked : narrow ? "cover" : "lyrics";
  const show = (next: string) => {
    rememberView(next);
    setParams(next === "lyrics" && !narrow ? {} : { view: next }, { replace: true });
  };
  const root = useRef<HTMLDivElement>(null);
  const fullscreen = useFullscreen(root);
  const [info, setInfo] = useState(false);

  const base = current?.track ?? item?.track;
  // The channel or station playing, or the Pandora station this song is from.
  const favorite = favoriteOf(base);
  const live = !!current?.live;
  // On live radio: the song on now (when the channel says), its cover (or the channel's), its album.
  const track = base && liveNow
    ? { ...base, title: liveNow.title || base.title, artist: liveNow.artist || base.title, artworkUrl: liveNow.artworkUrl ?? base.artworkUrl, album: liveNow.album }
    : live && base ? { ...base, album: undefined } : base;
  const accent = useAccent(track?.artworkUrl);
  const style = (accent ? { "--np-accent": accent } : {}) as CSSProperties;

  if (!track) {
    return (
      <div className="np np-empty" style={style}>
        <Backdrop url={undefined} />
        <div className="np-empty-text">
          <h1>Nothing playing</h1>
          <p>Pick something to play, and it shows here.</p>
          <Button variant="primary" onClick={() => navigate("/")}>Find something</Button>
        </div>
      </div>
    );
  }
  const lyricsFor: Track | undefined = live ? liveNow?.song : (item?.track ?? track);
  const song = live ? liveNow?.song : track;

  return (
    <div ref={root} className={`np np-view-${view} ${fullscreen.active ? "is-fullscreen" : ""}`} style={style}>
      <Backdrop url={track.artworkUrl} />
      {fullscreen.supported && (
        <button className="np-fullscreen" onClick={fullscreen.toggle} aria-label={fullscreen.active ? "Leave full screen" : "Full screen"} title={fullscreen.active ? "Leave full screen" : "Full screen"}>
          {fullscreen.active ? <Minimize2 size={20} /> : <Maximize2 size={20} />}
        </button>
      )}
      <div className="np-body">
        <section className="np-hero">
          <div className={`np-art ${playing ? "is-playing" : ""}`}>
            <Artwork url={track.artworkUrl} size={480} px={1024} alt="" className="np-artwork" />
          </div>
          {/* As wide as the cover: the channel above, the song with its buttons, then how far in. */}
          <div className="np-meta">
            {live && base && <Channel channel={base} />}
            <div className="np-meta-row">
              <div className="np-titles">
                <h1 title={track.title}>{track.title}</h1>
                <p className="np-artist">
                  {song?.artist ? <Link to={artistNamed(song.artist)}>{track.artist}</Link> : track.artist}
                  {track.album && (
                    <>
                      <span className="np-dot"> · </span>
                      {song?.artist ? <Link to={albumNamed(track.album, song.artist)}>{track.album}</Link> : <span>{track.album}</span>}
                    </>
                  )}
                </p>
              </div>
              <div className="np-actions">
                <StationThumbs track={base} size={20} />
                {favorite && <FavoriteButton track={favorite} size={22} />}
                <IconButton label="Details" onClick={() => setInfo(true)}><Info size={20} /></IconButton>
                {song && !song.sourceId.startsWith("channel:") && <TrackMenu track={song} />}
              </div>
            </div>
            {live ? <LiveSong /> : <p className="np-quality">{current ? current.description : buffering ? "Finding the best copy…" : ""}</p>}
          </div>
          <Controls live={live} />
        </section>

        <section className="np-side">
          <div className="np-tabs" role="tablist">
            <button role="tab" aria-selected={view === "cover"} className={view === "cover" ? "is-active" : ""} onClick={() => show("cover")}>Song</button>
            <button role="tab" aria-selected={view === "lyrics"} className={view === "lyrics" ? "is-active" : ""} onClick={() => show("lyrics")}>Lyrics</button>
            <button role="tab" aria-selected={view === "queue"} className={view === "queue" ? "is-active" : ""} onClick={() => show("queue")}>{live ? "Played" : "Up next"}</button>
          </div>
          {/* On narrow screens, the song stays in view above its lyrics or queue. */}
          <button className="np-mini" onClick={() => show("cover")} aria-label="Show the cover">
            <Artwork url={track.artworkUrl} size={52} px={128} alt="" />
            <span className="np-mini-text">
              <span className="np-mini-title">{track.title}</span>
              <span className="np-mini-artist">{track.artist}</span>
            </span>
          </button>
          {view === "queue" ? (
            live && base ? <Played channel={base} /> : <div className="np-panel np-queue"><Queue /></div>
          ) : lyricsFor ? (
            <Lyrics track={lyricsFor} seekable={!live} />
          ) : (
            <div className="np-panel np-note"><p>When the channel says which song is on, its lyrics show here.</p></div>
          )}
        </section>
      </div>
      {info && <TrackInfo track={base ?? track} onClose={() => setInfo(false)} />}
    </div>
  );
}

/** The live channel or station: its logo and name, and that it's live. */
function Channel({ channel }: { channel: Track }) {
  const number = channel.source === "SIRIUSXM" ? channel.artist.replace(/^SiriusXM( · )?/, "") : "";
  return (
    <div className="np-channel">
      <Artwork url={channel.artworkUrl} size={28} px={64} alt="" />
      <span className="np-channel-name" title={channel.title}>{channel.title}</span>
      {number && <span className="np-channel-number">{number}</span>}
      <span className="live-pill">Live</span>
    </div>
  );
}

/** How far into the song on the radio the stream is, when the channel says how long it is. */
function LiveSong() {
  const now = usePlayer((s) => s.liveNow);
  usePlayer((s) => s.position); // Moves along with the stream.
  const clock = player.liveClock();
  const length = now?.durationMs ?? 0;
  if (!now?.startMs || length <= 0 || clock == null) return null;
  const into = Math.min(Math.max(clock - now.startMs, 0), length);
  return (
    <div className="np-live-song" aria-label={`${duration(into)} of ${duration(length)}`}>
      <span>{duration(into)}</span>
      <div className="np-live-bar"><div style={{ width: `${(into / length) * 100}%` }} /></div>
      <span>{duration(length)}</span>
    </div>
  );
}

/**
 * What the channel played before: SiriusXM's last three hours, or for a radio station what it
 * said since it was tuned in. Any song can be saved, played or looked up.
 */
function Played({ channel }: { channel: Track }) {
  const nowTitle = usePlayer((s) => s.liveNow?.title);
  const history = usePlayer((s) => s.liveHistory);
  const sxm = channel.source === "SIRIUSXM" && channel.sourceId.startsWith("channel:") && !!channel.via;
  const [recent, setRecent] = useState<NowView[] | null>(null);

  useEffect(() => {
    if (!sxm) return;
    let cancelled = false;
    const [type, id] = splitTwo(channel.sourceId.slice("channel:".length));
    api.channelRecent(channel.via!, type, id, player.liveClock() ?? Date.now()).then(
      (songs) => !cancelled && setRecent(songs),
      () => !cancelled && setRecent([]),
    );
    return () => { cancelled = true; };
  }, [sxm, channel.via, channel.sourceId, nowTitle]);

  const clock = player.liveClock() ?? Date.now();
  const rows: { title: string; artist: string; artworkUrl?: string; song?: Track; at?: number }[] = sxm
    ? (recent ?? []).map((r) => ({ ...r, at: clock - r.startMs }))
    : history.map((h) => ({ ...h, at: h.heardAt ? Date.now() - h.heardAt : undefined }));

  if (sxm && recent === null) return <div className="np-panel np-note"><Spinner /></div>;
  if (!rows.length) {
    return (
      <div className="np-panel np-note">
        <p className="np-note-title">Nothing yet</p>
        <p>{sxm ? "The channel hasn't said what it played before this." : "Songs the station plays while you listen are listed here."}</p>
      </div>
    );
  }
  return (
    <div className="np-panel np-played">
      <ol>
        {rows.map((row, i) => (
          <li key={`${i}-${row.title}`} className="np-played-row">
            <Artwork url={row.artworkUrl ?? channel.artworkUrl} size={48} px={128} alt="" />
            <div className="np-played-text">
              <span className="np-played-title">{row.title}</span>
              <span className="np-played-artist">{row.artist}</span>
            </div>
            {row.at != null && <span className="np-played-when">{ago(row.at)}</span>}
            {row.song && <TrackMenu track={row.song} />}
          </li>
        ))}
      </ol>
    </div>
  );
}

/** "Just now", "4 min ago", "1 h 12 min ago". */
function ago(ms: number): string {
  const minutes = Math.round(ms / 60_000);
  if (minutes < 1) return "Just now";
  if (minutes < 60) return `${minutes} min ago`;
  return `${Math.floor(minutes / 60)} h${minutes % 60 ? ` ${minutes % 60} min` : ""} ago`;
}

/** The cover, huge and blurred and slowly drifting, behind everything; songs crossfade into each other. */
function Backdrop({ url }: { url?: string }) {
  const src = art(url, 300);
  const [layers, setLayers] = useState<{ id: number; src?: string }[]>([{ id: 0, src }]);
  useEffect(() => {
    setLayers((now) => (now[now.length - 1]?.src === src ? now : [...now.slice(-1), { id: (now[now.length - 1]?.id ?? 0) + 1, src }]));
  }, [src]);
  return (
    <div className="np-backdrop" aria-hidden>
      {layers.map((layer) => (
        <div key={layer.id} className="np-backdrop-layer">
          {layer.src && (
            <>
              <img src={layer.src} alt="" className="np-blob np-blob-a" />
              <img src={layer.src} alt="" className="np-blob np-blob-b" />
            </>
          )}
        </div>
      ))}
      <div className="np-shade" />
    </div>
  );
}

/** Play, skip and the time: in full screen, where the player bar isn't. */
function Controls({ live }: { live: boolean }) {
  const playing = usePlayer((s) => s.playing);
  const seek = useSeek();
  return (
    <div className="np-controls">
      {!live && seek.max > 0 && (
        <div className="np-progress">
          <span>{duration(seek.shown * 1000)}</span>
          <input {...seek.input} aria-label="Position" style={{ "--np-progress": `${seek.fill}%` } as CSSProperties} />
          <span>{duration(seek.max * 1000)}</span>
        </div>
      )}
      <div className="np-buttons">
        <button onClick={() => player.previous()} aria-label="Previous" disabled={live}><SkipBack size={26} fill="currentColor" /></button>
        <button className="np-play" onClick={() => player.toggle()} aria-label={playing ? "Pause" : "Play"}>
          {playing ? <Pause size={30} fill="currentColor" /> : <Play size={30} fill="currentColor" />}
        </button>
        <button onClick={() => player.next()} aria-label="Next" disabled={live}><SkipForward size={26} fill="currentColor" /></button>
      </div>
    </div>
  );
}

/**
 * The song's lyrics. Timed ones follow along (the line being sung stays in view without moving
 * anything else) and any line can be tapped to play from there; scrolling by hand pauses the
 * following for a few seconds.
 */
function Lyrics({ track, seekable }: { track: Track; seekable: boolean }) {
  const [lyrics, setLyrics] = useState<LyricsView | null | undefined>(undefined);
  const position = usePlayer((s) => s.position);
  const box = useRef<HTMLDivElement>(null);
  const list = useRef<HTMLOListElement>(null);
  const handScrolledAt = useRef(0);
  const [wandered, setWandered] = useState(false);
  const key = `${track.via}|${track.source}|${track.sourceId}`;

  useEffect(() => {
    let cancelled = false;
    setLyrics(undefined);
    box.current?.scrollTo({ top: 0 });
    api.lyrics(track).then((l) => !cancelled && setLyrics(l), () => !cancelled && setLyrics(null));
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  const lines = lyrics?.lines ?? [];
  const synced = !!lyrics?.synced;
  // Lines are shown a moment early, as the singer breathes in.
  const ms = position * 1000 + 250;
  const currentLine = synced ? lines.findLastIndex((l) => l.timeMs != null && l.timeMs <= ms) : -1;

  const follow = (smooth: boolean) => {
    const container = box.current;
    const line = list.current?.children[Math.max(currentLine, 0)] as HTMLElement | undefined;
    if (!container || !line) return;
    // Only the lyrics panel scrolls: never the page around it.
    const top = line.offsetTop - container.clientHeight * 0.36 + line.offsetHeight / 2;
    container.scrollTo({ top: Math.max(0, top), behavior: smooth && !reducedMotion() ? "smooth" : "auto" });
  };

  useEffect(() => {
    if (!synced || currentLine < 0) return;
    if (Date.now() - handScrolledAt.current < HAND_SCROLL_PAUSE_MS) return;
    setWandered(false);
    follow(true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentLine, synced]);

  // A panel that was hidden (another tab) or resized has lost its place: find the line again.
  useEffect(() => {
    const container = box.current;
    if (!container || !synced || currentLine < 0 || typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(() => {
      if (Date.now() - handScrolledAt.current >= HAND_SCROLL_PAUSE_MS) follow(false);
    });
    observer.observe(container);
    return () => observer.disconnect();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentLine, synced, lyrics]);

  const handScroll = () => {
    if (!synced) return;
    handScrolledAt.current = Date.now();
    setWandered(true);
  };

  if (lyrics === undefined) return <div className="np-panel np-note"><Spinner /></div>;
  if (!lines.length) {
    return (
      <div className="np-panel np-note">
        <p className="np-note-title">No lyrics for this one</p>
        <p>Neither your library nor LRCLIB has them. Enjoy the music.</p>
      </div>
    );
  }
  return (
    <div className={`np-panel np-lyrics ${synced ? "is-synced" : "is-plain"}`}>
      <div ref={box} className="np-lyrics-scroll" onWheel={handScroll} onTouchMove={handScroll}>
        {synced && <div className="np-lyrics-lead" aria-hidden />}
        <ol ref={list}>
          {lines.map((line, i) => {
            const state = i === currentLine ? "is-current" : i < currentLine ? "is-past" : "is-future";
            const far = synced && currentLine >= 0 && Math.abs(i - currentLine) > 2 ? "is-far" : "";
            const text = line.text.trim();
            const canSeek = synced && seekable && line.timeMs != null;
            return (
              <li key={i} className={`${synced ? state : ""} ${far}`}>
                {canSeek ? (
                  <button
                    onClick={() => {
                      player.seek(line.timeMs! / 1000);
                      handScrolledAt.current = 0;
                      setWandered(false);
                    }}
                    title={`Play from ${duration(line.timeMs!)}`}
                  >
                    {text || <Breath />}
                  </button>
                ) : (
                  <span>{text || <Breath />}</span>
                )}
              </li>
            );
          })}
        </ol>
        <p className="np-lyrics-source">{synced ? "" : "These lyrics aren't timed. "}Lyrics from {lyrics?.source}</p>
        {synced && <div className="np-lyrics-tail" aria-hidden />}
      </div>
      {wandered && currentLine >= 0 && (
        <button className="np-back" onClick={() => { handScrolledAt.current = 0; setWandered(false); follow(true); }}>Back to the song</button>
      )}
    </div>
  );
}

/** An instrumental break. */
function Breath() {
  return <span className="np-breath" aria-label="Instrumental"><i /><i /><i /></span>;
}

const HAND_SCROLL_PAUSE_MS = 4_000;

function reducedMotion() {
  return window.matchMedia?.("(prefers-reduced-motion: reduce)").matches ?? false;
}

const VIEW_KEY = "samm.nowPlaying.view";

function rememberedView(): string | null {
  try {
    return localStorage.getItem(VIEW_KEY);
  } catch {
    return null;
  }
}

function rememberView(view: string) {
  try {
    localStorage.setItem(VIEW_KEY, view);
  } catch {
    // Not kept (a private window); the tab still changes.
  }
}

/** Whether [query] matches the window, kept up to date. */
function useMedia(query: string): boolean {
  const get = () => typeof window !== "undefined" && !!window.matchMedia?.(query).matches;
  const [matches, setMatches] = useState(get);
  useEffect(() => {
    const list = window.matchMedia?.(query);
    if (!list) return;
    const changed = () => setMatches(list.matches);
    changed();
    list.addEventListener("change", changed);
    return () => list.removeEventListener("change", changed);
  }, [query]);
  return matches;
}

/** Full screen for the page, where the browser allows it. */
function useFullscreen(element: React.RefObject<HTMLElement | null>) {
  const [active, setActive] = useState(false);
  useEffect(() => {
    const changed = () => setActive(document.fullscreenElement != null && document.fullscreenElement === element.current);
    document.addEventListener("fullscreenchange", changed);
    return () => document.removeEventListener("fullscreenchange", changed);
  }, [element]);
  return {
    active,
    supported: typeof document !== "undefined" && !!document.fullscreenEnabled,
    toggle: () => {
      if (document.fullscreenElement) void document.exitFullscreen();
      else void element.current?.requestFullscreen().catch(() => undefined);
    },
  };
}

/**
 * The cover's main colour, made bright enough to read on the dark backdrop: it tints the line
 * being sung, the glow around the cover and the progress bar.
 */
function useAccent(url?: string): string | null {
  const [accent, setAccent] = useState<string | null>(null);
  const src = art(url, 64);
  useEffect(() => {
    if (!src) { setAccent(null); return; }
    let cancelled = false;
    const image = new Image();
    image.decoding = "async";
    image.onload = () => {
      if (cancelled) return;
      try {
        const size = 24;
        const canvas = document.createElement("canvas");
        canvas.width = size;
        canvas.height = size;
        const context = canvas.getContext("2d", { willReadFrequently: true });
        if (!context) return;
        context.drawImage(image, 0, 0, size, size);
        setAccent(pickAccent(context.getImageData(0, 0, size, size).data));
      } catch {
        setAccent(null); // An image from elsewhere can't be read; the default colours stay.
      }
    };
    image.onerror = () => !cancelled && setAccent(null);
    image.src = src;
    return () => { cancelled = true; };
  }, [src]);
  return accent;
}

/** The most colourful hue in the pixels (weighted towards vivid ones), as a light, readable colour. */
export function pickAccent(pixels: Uint8ClampedArray): string | null {
  let x = 0, y = 0, weight = 0, satTotal = 0;
  for (let i = 0; i < pixels.length; i += 4) {
    const [h, s, l] = hsl(pixels[i], pixels[i + 1], pixels[i + 2]);
    if (l < 0.08 || l > 0.95) continue;
    const w = s * s * (1 - Math.abs(l - 0.5));
    x += Math.cos(h * 2 * Math.PI) * w;
    y += Math.sin(h * 2 * Math.PI) * w;
    weight += w;
    satTotal += s;
  }
  if (weight < 0.5) return null; // Black and white: keep the app's own colours.
  const hue = ((Math.atan2(y, x) / (2 * Math.PI)) + 1) % 1;
  const saturation = Math.min(0.9, Math.max(0.5, satTotal / (pixels.length / 4) + 0.3));
  return `hsl(${Math.round(hue * 360)} ${Math.round(saturation * 100)}% 74%)`;
}

function hsl(r: number, g: number, b: number): [number, number, number] {
  r /= 255; g /= 255; b /= 255;
  const max = Math.max(r, g, b), min = Math.min(r, g, b);
  const l = (max + min) / 2;
  if (max === min) return [0, 0, l];
  const d = max - min;
  const s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
  const h = max === r ? (g - b) / d + (g < b ? 6 : 0) : max === g ? (b - r) / d + 2 : (r - g) / d + 4;
  return [h / 6, s, l];
}
