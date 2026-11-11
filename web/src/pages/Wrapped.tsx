import { useCallback, useEffect, useMemo, useState, type ReactNode } from "react";
import { Link } from "react-router-dom";
import { ChevronRight, ListPlus, Pause, Play, RotateCcw, Sparkles, X } from "lucide-react";
import { api } from "../lib/api";
import { SOURCE_LABELS } from "../lib/format";
import type { SourceType, Wrapped as WrappedData, WrappedArtist, WrappedShare } from "../lib/types";
import { Artwork } from "../components/Artwork";
import { albumNamed, artistNamed, CardRow } from "../components/Cards";
import { TrackList } from "../components/TrackList";
import { Button, Empty, errorToast, PageHeader, Spinner, toast, useLoad } from "../components/ui";

const num = (n: number) => n.toLocaleString();

/** 0–23 as people say it. */
export function hourName(hour: number): string {
  if (hour === 0) return "midnight";
  if (hour === 12) return "noon";
  return hour < 12 ? `${hour} am` : `${hour - 12} pm`;
}

const SHORT_HOURS = (h: number) => (h === 0 ? "12a" : h === 12 ? "12p" : h < 12 ? `${h}a` : `${h - 12}p`);
const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

const sourceName = (name: string) => SOURCE_LABELS[name as SourceType] ?? name;
const deviceName = (name: string) => (name === "web" ? "Web player" : name === "android" ? "Phone app" : name === "desktop" ? "Desktop app" : name);
const dayName = (date: string) => new Date(`${date}T12:00:00`).toLocaleDateString(undefined, { weekday: "long", month: "long", day: "numeric" });
const peakHour = (hours: number[]) => hours.reduce((best, m, h) => (m > hours[best] ? h : best), 0);

/** Days, to one decimal place when there are few. */
function daysOf(minutes: number): string {
  const days = minutes / 1440;
  return days < 10 ? days.toFixed(1).replace(/\.0$/, "") : num(Math.round(days));
}

async function saveTopSongs(data: WrappedData) {
  const name = `Your Top Songs ${data.year}`;
  try {
    await api.createPlaylist(name, data.topSongs.map((s) => s.track));
    toast(`Saved "${name}" with ${data.topSongs.length} songs`);
  } catch (e) {
    errorToast(e);
  }
}

/** Wrapped: the year in music, as a story to play through and a recap to look things up in. */
export function Wrapped() {
  const [year, setYear] = useState<number | undefined>();
  const { data, error, loading } = useLoad(() => api.wrapped(year), [year]);
  const [story, setStory] = useState(false);

  return (
    <div className="page wrapped">
      <PageHeader
        title="Wrapped"
        subtitle="Your year in music, from everything you played here (live radio too)"
        actions={data && data.years.length > 1 && (
          <select className="wrapped-year" aria-label="Year" value={data.year} onChange={(e) => setYear(Number(e.target.value))}>
            {data.years.map((y) => <option key={y} value={y}>{y}</option>)}
          </select>
        )}
      />
      {loading && !data && <div className="center-pad"><Spinner /></div>}
      {error && <div className="notice notice-error">{error}</div>}
      {data && data.listens === 0 && (
        <Empty icon={<Sparkles size={40} />} title={`Nothing to wrap for ${data.year} yet`}>
          Songs you listen to for a while, and songs heard on live radio, build up your year here.
        </Empty>
      )}
      {data && data.listens > 0 && <Recap data={data} onStory={() => setStory(true)} />}
      {story && data && <Story data={data} onClose={() => setStory(false)} />}
    </div>
  );
}

function Recap({ data, onStory }: { data: WrappedData; onStory: () => void }) {
  const [saving, setSaving] = useState(false);
  const peak = peakHour(data.hours);
  return (
    <>
      <section className="wrapped-hero">
        <div className="wrapped-hero-text">
          <span className="wrapped-eyebrow">{data.year}{data.soFar ? " so far" : ""}</span>
          <h2>{num(data.minutes)} minutes of music</h2>
          <p>{num(data.songs)} songs · {num(data.artists)} artists · {num(data.days)} days with music</p>
          <span className="wrapped-chip"><Sparkles size={16} /> {data.personality.name}</span>
        </div>
        <div className="wrapped-hero-actions">
          <Button variant="primary" onClick={onStory}><Play size={18} /> Play your story</Button>
          {data.topSongs.length > 0 && (
            <Button busy={saving} onClick={async () => { setSaving(true); await saveTopSongs(data); setSaving(false); }}>
              <ListPlus size={18} /> Save top songs as a playlist
            </Button>
          )}
        </div>
      </section>

      <div className="wrapped-columns">
        <section>
          <h2 className="section-title">Top songs</h2>
          <TrackList numbered rows={data.topSongs.slice(0, 10).map((s) => ({ key: `${s.track.source}:${s.track.sourceId}`, track: s.track }))} />
        </section>
        <section>
          <h2 className="section-title">Top artists</h2>
          <ol className="wrapped-ranks">
            {data.topArtists.map((a, i) => <ArtistRank key={a.name} artist={a} rank={i + 1} />)}
          </ol>
        </section>
      </div>

      {data.topAlbums.length > 0 && (
        <section>
          <h2 className="section-title">Top albums</h2>
          <CardRow>
            {data.topAlbums.map((a) => (
              <Link key={`${a.title}-${a.artist}`} to={albumNamed(a.title, a.artist)} className="card">
                <Artwork url={a.artworkUrl ?? undefined} size={160} />
                <div className="card-title" title={a.title}>{a.title}</div>
                <div className="card-sub">{a.artist} · {num(a.minutes)} min</div>
              </Link>
            ))}
          </CardRow>
        </section>
      )}

      <div className="wrapped-columns">
        <section className="panel wrapped-panel">
          <h2>When you listen</h2>
          <p className="muted">Mostly around {hourName(peak)}{data.weekdays.some((m) => m > 0) ? `, and most on ${weekdayName(data.weekdays)}s` : ""}.</p>
          <BarChart values={data.hours} label={(h) => hourName(h)} tick={(h) => (h % 6 === 0 ? SHORT_HOURS(h) : "")} title="Minutes by hour of the day" />
          <BarChart values={data.months} label={(m) => MONTHS[m]} tick={(m) => MONTHS[m].slice(0, 1)} title="Minutes by month" />
        </section>
        <section className="panel wrapped-panel">
          <h2>Where it came from</h2>
          <Shares items={data.sources} name={sourceName} />
          <h3>On</h3>
          <Shares items={data.devices} name={deviceName} />
          {data.topStations.length > 0 && <>
            <h3>Live radio</h3>
            <Shares items={data.topStations} name={(n) => n} />
          </>}
        </section>
      </div>

      <section>
        <h2 className="section-title">Highlights</h2>
        <div className="wrapped-tiles">
          {data.biggestDay && <Tile label="Biggest day" value={`${num(data.biggestDay.minutes)} min`}>{dayName(data.biggestDay.date)}, {data.biggestDay.listens} listens</Tile>}
          <Tile label="Longest streak" value={`${data.longestStreak} ${data.longestStreak === 1 ? "day" : "days"}`}>in a row with music</Tile>
          {data.onRepeat && <Tile label="On repeat" value={`${data.onRepeat.plays}×`}>{data.onRepeat.track.title} on {dayName(data.onRepeat.date)}</Tile>}
          {data.firstSong && <Tile label="First song of the year" value={data.firstSong.track.title}>{data.firstSong.track.artist}</Tile>}
          {data.newArtists > 0 && <Tile label="Discoveries" value={`${num(data.newArtists)} new artists`}>Favourite find: {data.discoveries[0]?.name}</Tile>}
          <Tile label="Your listening personality" value={data.personality.name}>{data.personality.description}</Tile>
        </div>
      </section>
    </>
  );
}

function weekdayName(weekdays: number[]): string {
  const best = peakHour(weekdays);
  // Monday first; 2024-01-01 was a Monday.
  return new Date(2024, 0, 1 + best).toLocaleDateString(undefined, { weekday: "long" });
}

function ArtistRank({ artist, rank }: { artist: WrappedArtist; rank: number }) {
  return (
    <li>
      <Link to={artistNamed(artist.name)} className="wrapped-rank">
        <span className="wrapped-rank-n">{rank}</span>
        <Artwork url={artist.artworkUrl ?? undefined} size={48} className="round" />
        <span className="wrapped-rank-text">
          <span className="track-title">{artist.name}</span>
          <span className="muted">{num(artist.minutes)} min · {num(artist.plays)} plays</span>
        </span>
      </Link>
    </li>
  );
}

function Tile({ label, value, children }: { label: string; value: string; children?: ReactNode }) {
  return (
    <div className="wrapped-tile">
      <span className="wrapped-tile-label">{label}</span>
      <strong>{value}</strong>
      {children && <span className="muted">{children}</span>}
    </div>
  );
}

/** One series of minutes as thin bars on a baseline; each bar says its value on hover. */
function BarChart({ values, label, tick, title }: { values: number[]; label: (i: number) => string; tick: (i: number) => string; title: string }) {
  const max = Math.max(1, ...values);
  return (
    <figure className="wrapped-chart">
      <figcaption>{title}</figcaption>
      <div className="wrapped-bars" role="list">
        {values.map((v, i) => (
          <div key={i} className="wrapped-bar" role="listitem" tabIndex={0} aria-label={`${label(i)}: ${num(v)} minutes`}>
            <span className="wrapped-bar-fill" style={{ height: `${Math.max(v > 0 ? 3 : 0, (v / max) * 100)}%` }} />
            <span className="wrapped-tip">{label(i)}<br />{num(v)} min</span>
          </div>
        ))}
      </div>
      <div className="wrapped-ticks" aria-hidden="true">
        {values.map((_, i) => <span key={i}>{tick(i)}</span>)}
      </div>
    </figure>
  );
}

/** Shares of the year's minutes, largest first, labelled with minutes and percent. */
function Shares({ items, name }: { items: WrappedShare[]; name: (n: string) => string }) {
  const total = items.reduce((t, s) => t + s.minutes, 0) || 1;
  const max = Math.max(1, ...items.map((s) => s.minutes));
  return (
    <ul className="wrapped-shares">
      {items.slice(0, 6).map((s) => (
        <li key={s.name}>
          <span className="wrapped-share-name" title={name(s.name)}>{name(s.name)}</span>
          <span className="wrapped-share-value">{num(s.minutes)} min · {Math.round((s.minutes / total) * 100)}%</span>
          <span className="wrapped-share-bar"><span style={{ width: `${(s.minutes / max) * 100}%` }} /></span>
        </li>
      ))}
    </ul>
  );
}

// ---- The story ----

const SLIDE_MS = 7000;
const BACKDROPS = [
  "linear-gradient(160deg, #5b3fd1 0%, #8e3f5c 100%)",
  "linear-gradient(160deg, #1d1b6b 0%, #0f766e 100%)",
  "linear-gradient(160deg, #8e3f5c 0%, #d97706 100%)",
  "linear-gradient(160deg, #0b1220 0%, #5b3fd1 100%)",
  "linear-gradient(160deg, #0f766e 0%, #5b3fd1 100%)",
  "linear-gradient(160deg, #3b0764 0%, #be185d 100%)",
];

interface Slide { key: string; content: ReactNode }

function slidesOf(data: WrappedData): Slide[] {
  const slides: Slide[] = [];
  const when = `${data.year}${data.soFar ? " so far" : ""}`;
  slides.push({ key: "intro", content: <>
    <Sparkles size={48} />
    <span className="story-eyebrow">{when}</span>
    <h2 className="story-big">Your year in music</h2>
    <p>Let's look back at what you played.</p>
  </> });
  slides.push({ key: "minutes", content: <>
    <span className="story-eyebrow">You listened for</span>
    <h2 className="story-huge">{num(data.minutes)}</h2>
    <h3>minutes</h3>
    <p>That's {daysOf(data.minutes)} days of music: {num(data.songs)} songs by {num(data.artists)} artists, on {num(data.days)} different days.</p>
  </> });
  const song = data.topSongs[0];
  if (song) slides.push({ key: "song", content: <>
    <span className="story-eyebrow">Your song of the year</span>
    <Artwork url={song.track.artworkUrl} size={240} px={480} className="story-art" />
    <h2 className="story-big">{song.track.title}</h2>
    <p>{song.track.artist}</p>
    <p className="story-note">Played {num(song.plays)} {song.plays === 1 ? "time" : "times"}</p>
  </> });
  if (data.topSongs.length > 1) slides.push({ key: "songs", content: <>
    <span className="story-eyebrow">Your top songs</span>
    <ol className="story-list">
      {data.topSongs.slice(0, 5).map((s, i) => (
        <li key={i}><span className="story-rank">{i + 1}</span><Artwork url={s.track.artworkUrl} size={52} /><span><strong>{s.track.title}</strong><br />{s.track.artist}</span></li>
      ))}
    </ol>
  </> });
  const artist = data.topArtists[0];
  if (artist) slides.push({ key: "artist", content: <>
    <span className="story-eyebrow">Your top artist</span>
    <Artwork url={artist.artworkUrl ?? undefined} size={240} px={480} className="story-art round" />
    <h2 className="story-big">{artist.name}</h2>
    <p>{num(artist.minutes)} minutes together{artist.topSong ? `, most of all "${artist.topSong}"` : ""}.</p>
  </> });
  if (data.topArtists.length > 1) slides.push({ key: "artists", content: <>
    <span className="story-eyebrow">Your top artists</span>
    <ol className="story-list">
      {data.topArtists.slice(0, 5).map((a, i) => (
        <li key={a.name}><span className="story-rank">{i + 1}</span><Artwork url={a.artworkUrl ?? undefined} size={52} className="round" /><span><strong>{a.name}</strong><br />{num(a.minutes)} min</span></li>
      ))}
    </ol>
  </> });
  const station = data.topStations[0];
  if (station) slides.push({ key: "station", content: <>
    <span className="story-eyebrow">Your station of the year</span>
    <h2 className="story-big">{station.name}</h2>
    <p>{num(Math.round(station.minutes / 60))} hours tuned in{data.topStations.length > 1 ? `, then ${data.topStations.slice(1, 3).map((s) => s.name).join(" and ")}` : ""}.</p>
  </> });
  if (data.newArtists > 0) slides.push({ key: "discoveries", content: <>
    <span className="story-eyebrow">New to you</span>
    <h2 className="story-huge">{num(data.newArtists)}</h2>
    <h3>{data.newArtists === 1 ? "artist discovered" : "artists discovered"}</h3>
    <p>Your favourite find: <strong>{data.discoveries[0]?.name}</strong>.</p>
  </> });
  const peak = peakHour(data.hours);
  slides.push({ key: "clock", content: <>
    <span className="story-eyebrow">Your music clock</span>
    <h2 className="story-big">Peak time: {hourName(peak)}</h2>
    <div className="story-bars" aria-hidden="true">
      {data.hours.map((m, h) => <span key={h} style={{ height: `${Math.max(m > 0 ? 4 : 0, (m / Math.max(1, ...data.hours)) * 100)}%` }} />)}
    </div>
    <p>{data.biggestDay ? `Your biggest day was ${dayName(data.biggestDay.date)}, with ${num(data.biggestDay.minutes)} minutes.` : ""}</p>
  </> });
  slides.push({ key: "streak", content: <>
    <span className="story-eyebrow">Keeping it going</span>
    <h2 className="story-huge">{data.longestStreak}</h2>
    <h3>{data.longestStreak === 1 ? "day" : "days in a row"}</h3>
    {data.onRepeat
      ? <p>And one day you played "{data.onRepeat.track.title}" {data.onRepeat.plays} times. No judgement.</p>
      : <p>Your longest run of days with music.</p>}
  </> });
  slides.push({ key: "personality", content: <>
    <span className="story-eyebrow">Your listening personality</span>
    <h2 className="story-big">{data.personality.name}</h2>
    <p>{data.personality.description}</p>
  </> });
  slides.push({ key: "summary", content: <Summary data={data} /> });
  return slides;
}

/** The last card: the year on one screen. */
function Summary({ data }: { data: WrappedData }) {
  return (
    <div className="story-summary">
      <span className="story-eyebrow">{data.year}{data.soFar ? " so far" : ""} Wrapped</span>
      <div className="story-summary-cols">
        <div>
          <h4>Top artists</h4>
          <ol>{data.topArtists.slice(0, 5).map((a) => <li key={a.name}>{a.name}</li>)}</ol>
        </div>
        <div>
          <h4>Top songs</h4>
          <ol>{data.topSongs.slice(0, 5).map((s, i) => <li key={i}>{s.track.title}</li>)}</ol>
        </div>
      </div>
      <div className="story-summary-cols">
        <div><h4>Minutes</h4><strong>{num(data.minutes)}</strong></div>
        <div><h4>Personality</h4><strong>{data.personality.name}</strong></div>
      </div>
    </div>
  );
}

function Story({ data, onClose }: { data: WrappedData; onClose: () => void }) {
  const slides = useMemo(() => slidesOf(data), [data]);
  const [index, setIndex] = useState(0);
  const [paused, setPaused] = useState(false);
  const last = index === slides.length - 1;
  const go = useCallback((to: number) => setIndex(Math.max(0, Math.min(slides.length - 1, to))), [slides.length]);

  // On to the next slide after a while, unless paused (or the page is hidden).
  useEffect(() => {
    if (paused || last) return;
    const timer = window.setTimeout(() => { if (!document.hidden) go(index + 1); }, SLIDE_MS);
    return () => window.clearTimeout(timer);
  }, [index, paused, last, go]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
      else if (e.key === "ArrowRight") go(index + 1);
      else if (e.key === "ArrowLeft") go(index - 1);
      else if (e.key === " ") { e.preventDefault(); setPaused((p) => !p); }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [index, go, onClose]);

  const slide = slides[index];
  return (
    <div className="story" role="dialog" aria-modal="true" aria-label={`${data.year} Wrapped`} style={{ background: BACKDROPS[index % BACKDROPS.length] }}>
      <div className="story-progress" aria-hidden="true">
        {slides.map((s, i) => (
          <span key={s.key}><span className={i < index ? "done" : i === index ? (paused || last ? "done-now" : "now") : ""} style={i === index ? { animationDuration: `${SLIDE_MS}ms` } : undefined} /></span>
        ))}
      </div>
      <div className="story-controls">
        {!last && <button className="story-btn" onClick={() => setPaused(!paused)} aria-label={paused ? "Play" : "Pause"}>{paused ? <Play size={20} /> : <Pause size={20} />}</button>}
        <button className="story-btn" onClick={onClose} aria-label="Close"><X size={22} /></button>
      </div>
      {/* Tapping the left third goes back; anywhere else, on. */}
      <button className="story-tap story-tap-back" aria-label="Previous" onClick={() => go(index - 1)} />
      <button className="story-tap story-tap-next" aria-label="Next" onClick={() => go(index + 1)} disabled={last} />
      <div className="story-slide" key={slide.key}>{slide.content}</div>
      {last && (
        <div className="story-actions">
          {data.topSongs.length > 0 && <Button variant="primary" onClick={() => void saveTopSongs(data)}><ListPlus size={18} /> Save top songs as a playlist</Button>}
          <Button onClick={() => go(0)}><RotateCcw size={18} /> Watch again</Button>
          <Button variant="ghost" onClick={onClose}>Done</Button>
        </div>
      )}
      {!last && <ChevronRight className="story-hint" size={28} aria-hidden="true" />}
    </div>
  );
}
