import { useEffect, useMemo, useState } from "react";
import { Lock, MapPin, Radio as RadioIcon, Search as SearchIcon } from "lucide-react";
import { api } from "../lib/api";
import type { ArtistView, ChannelView, SourceView, Track } from "../lib/types";
import { player, usePlayer } from "../player/player";
import { Artwork } from "../components/Artwork";
import { Button, Empty, errorToast, PageHeader, Spinner, useLoad } from "../components/ui";

type Tab = "sxm" | "pandora" | "radio";

/** Live radio: SiriusXM channels, Pandora artist stations through SiriusXM, and radio stations. */
export function Radio() {
  const sources = useLoad(() => api.sources(), []);
  const usable = (sources.data ?? []).filter((s) => s.enabled && !s.hidden);
  const siriusXm = usable.filter((s) => s.kind === "SIRIUSXM");
  const radio = usable.find((s) => s.kind === "RADIO");
  const [tab, setTab] = useState<Tab | null>(null);
  const [sxmId, setSxmId] = useState<string | null>(null);
  const shown: Tab = tab ?? (siriusXm.length ? "sxm" : "radio");
  const sxm = siriusXm.find((s) => s.id === sxmId) ?? siriusXm[0];

  if (sources.loading && !sources.data) return <div className="center-pad"><Spinner /></div>;
  return (
    <div className="page">
      <PageHeader
        title="Radio"
        actions={siriusXm.length > 1 && (
          <select value={sxm?.id} onChange={(e) => setSxmId(e.target.value)} aria-label="SiriusXM account">
            {siriusXm.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
          </select>
        )}
      />
      <div className="tabs" role="tablist">
        <button role="tab" aria-selected={shown === "sxm"} className={shown === "sxm" ? "is-active" : ""} onClick={() => setTab("sxm")}>SiriusXM</button>
        <button role="tab" aria-selected={shown === "pandora"} className={shown === "pandora" ? "is-active" : ""} onClick={() => setTab("pandora")}>Pandora stations</button>
        <button role="tab" aria-selected={shown === "radio"} className={shown === "radio" ? "is-active" : ""} onClick={() => setTab("radio")}>Local radio</button>
      </div>
      {shown !== "radio" && !sxm && (
        <Empty icon={<RadioIcon size={40} />} title="No SiriusXM account">
          Add your SiriusXM streaming account in Settings › Sources to hear its channels and Pandora artist stations here.
        </Empty>
      )}
      {shown === "sxm" && sxm && <Channels source={sxm} />}
      {shown === "pandora" && sxm && <Stations source={sxm} />}
      {shown === "radio" && (radio ? <RadioStations source={radio} /> : <Empty title="Radio is off">An admin can add the Radio source in Admin › Shared sources.</Empty>)}
    </div>
  );
}

function Channels({ source }: { source: SourceView }) {
  const { data, error, loading } = useLoad(() => api.channels(source.id), [source.id]);
  const [filter, setFilter] = useState("");
  const [showLocked, setShowLocked] = useState(false);
  const playingKey = usePlayer((s) => s.queue[s.index]?.track.sourceId);
  const groups = useMemo(() => {
    const words = filter.toLowerCase().split(/\s+/).filter(Boolean);
    const matches = (c: ChannelView) => words.every((w) => `${c.track.title} ${c.number ?? ""} ${c.category}`.toLowerCase().includes(w));
    const byCategory = new Map<string, ChannelView[]>();
    for (const c of data ?? []) {
      if ((c.locked && !showLocked) || !matches(c)) continue;
      const key = c.category || "Other";
      byCategory.set(key, [...(byCategory.get(key) ?? []), c]);
    }
    return [...byCategory.entries()];
  }, [data, filter, showLocked]);

  if (loading && !data) return <div className="center-pad"><Spinner /> Signing in to SiriusXM…</div>;
  if (error) return <div className="notice notice-error">{error}</div>;
  const locked = (data ?? []).filter((c) => c.locked).length;
  return (
    <>
      <div className="filter-row">
        <div className="search-box small">
          <SearchIcon size={18} aria-hidden />
          <input value={filter} onChange={(e) => setFilter(e.target.value)} placeholder="Filter channels: name, number or genre" aria-label="Filter channels" />
        </div>
        {locked > 0 && <Button variant="ghost" onClick={() => setShowLocked(!showLocked)}>{showLocked ? "Hide" : "Show"} {locked} not in your plan</Button>}
      </div>
      {groups.map(([category, channels]) => (
        <section key={category}>
          <h2 className="section-title">{category}</h2>
          <div className="channel-grid">
            {channels.map((c) => (
              <button
                key={c.track.sourceId}
                className={`channel ${playingKey === c.track.sourceId ? "is-current" : ""} ${c.locked ? "is-locked" : ""}`}
                onClick={() => player.playLive(c.track)}
                disabled={c.locked}
                title={c.locked ? "Not in your SiriusXM plan" : `Play ${c.track.title}`}
              >
                <Artwork url={c.track.artworkUrl} size={64} />
                <span className="channel-text">
                  <span className="channel-name">{c.track.title}</span>
                  <span className="channel-number">{c.number != null ? `Ch ${c.number}` : ""}{c.locked && <Lock size={12} />}</span>
                </span>
              </button>
            ))}
          </div>
        </section>
      ))}
      {data && !groups.length && <Empty title="No channels match">Try another word.</Empty>}
    </>
  );
}

function Stations({ source }: { source: SourceView }) {
  const [query, setQuery] = useState("");
  const [artists, setArtists] = useState<ArtistView[] | null>(null);
  const [busy, setBusy] = useState(false);
  const [starting, setStarting] = useState<string | null>(null);

  const search = async (e?: React.FormEvent) => {
    e?.preventDefault();
    if (!query.trim()) return;
    setBusy(true);
    try { setArtists(await api.sxmArtists(source.id, query.trim())); } catch (err) { errorToast(err); }
    setBusy(false);
  };
  const start = async (artist: ArtistView) => {
    setStarting(artist.id);
    try {
      const { tracks } = await api.station(source.id, { artist });
      if (!tracks.length) throw new Error("SiriusXM sent nothing for this station");
      player.startStation(tracks);
    } catch (err) { errorToast(err); }
    setStarting(null);
  };

  return (
    <>
      <p className="muted">Music by and like one artist, through your SiriusXM account. Thumbs up and down shape what plays.</p>
      <form className="search-box" onSubmit={search}>
        <SearchIcon size={20} aria-hidden />
        <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="An artist to start a station from" aria-label="Artist" />
        {busy && <Spinner size={18} />}
      </form>
      {artists && !artists.length && <Empty title="No stations found">Try the artist's full name.</Empty>}
      <div className="card-grid">
        {(artists ?? []).map((a) => (
          <button key={a.id} className="card card-button" onClick={() => start(a)} disabled={starting != null}>
            <Artwork url={a.artworkUrl} size={180} />
            <div className="card-title">{a.name}</div>
            <div className="card-sub">{starting === a.id ? "Starting…" : a.stationId ? "Pandora station" : "Artist"}</div>
          </button>
        ))}
      </div>
    </>
  );
}

function RadioStations({ source }: { source: SourceView }) {
  const country = (navigator.language.split("-")[1] ?? "").toUpperCase() || undefined;
  const [query, setQuery] = useState("");
  const [label, setLabel] = useState(country ? `Popular in ${regionName(country)}` : "Popular");
  const [stations, setStations] = useState<Track[] | null>(null);
  const [busy, setBusy] = useState(false);
  const playingKey = usePlayer((s) => s.queue[s.index]?.track.sourceId);

  const run = async (title: string, load: () => Promise<Track[]>) => {
    setBusy(true);
    try { setStations(await load()); setLabel(title); } catch (err) { errorToast(err); }
    setBusy(false);
  };
  useEffect(() => { void run(label, () => api.radioSearch(source.id, "", country)); }, [source.id]); // eslint-disable-line react-hooks/exhaustive-deps

  const nearMe = () => {
    if (!navigator.geolocation) return errorToast(new Error("This browser can't tell where you are"));
    setBusy(true);
    navigator.geolocation.getCurrentPosition(
      (pos) => void run("Near you", () => api.radioNearby(source.id, pos.coords.latitude, pos.coords.longitude)),
      () => { setBusy(false); errorToast(new Error("Location wasn't shared, so stations near you can't be found")); },
      { timeout: 15_000, maximumAge: 600_000 },
    );
  };

  return (
    <>
      <div className="filter-row">
        <form className="search-box small" onSubmit={(e) => { e.preventDefault(); if (query.trim()) void run(`“${query.trim()}”`, () => api.radioSearch(source.id, query.trim())); }}>
          <SearchIcon size={18} aria-hidden />
          <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Station name, e.g. WFMU or jazz" aria-label="Search stations" />
        </form>
        <Button onClick={nearMe}><MapPin size={18} /> Near me</Button>
        {busy && <Spinner size={18} />}
      </div>
      <h2 className="section-title">{label}</h2>
      {stations && !stations.length && <Empty title="No stations found" />}
      <ol className="track-list">
        {(stations ?? []).map((s) => (
          <li key={s.sourceId} className={`track-row ${playingKey === s.sourceId ? "is-current" : ""}`}>
            <button className="track-play" aria-label={`Play ${s.title}`} onClick={() => player.playLive(s)}>
              <Artwork url={s.artworkUrl} size={44} />
            </button>
            <div className="track-text" onClick={() => player.playLive(s)}>
              <div className="track-title">{s.title}</div>
              <div className="track-sub">{s.artist}{s.album ? <span className="track-album"> · {s.album}</span> : null}</div>
            </div>
            <span className="badge track-source">{s.sourceId.startsWith("hls:") ? "HLS" : "Live"}</span>
            <span />
            <span />
          </li>
        ))}
      </ol>
      <p className="muted small">Stations from <a href="https://www.radio-browser.info/" target="_blank" rel="noreferrer">Radio Browser</a>, a free, open directory.</p>
    </>
  );
}

function regionName(code: string): string {
  try {
    return new Intl.DisplayNames(undefined, { type: "region" }).of(code) ?? code;
  } catch {
    return code;
  }
}
