import { useEffect, useRef, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { Play, Search as SearchIcon } from "lucide-react";
import { api } from "../lib/api";
import type { SearchResults, TopResult, Track } from "../lib/types";
import { TrackList } from "../components/TrackList";
import { Artwork } from "../components/Artwork";
import { AlbumCard, albumLink, ArtistCard, artistLink, CardRow } from "../components/Cards";
import { Button, Empty, errorToast, Spinner } from "../components/ui";
import { trackKey } from "../lib/format";
import { player } from "../player/player";

const SONGS_AT_FIRST = 5;

/**
 * Searches all of your music at once, the way streaming apps do: the likeliest match on top
 * (an artist, an album or a song), then songs, artists and albums, each shown once whichever
 * of your sources has it.
 */
export function Search() {
  const [params, setParams] = useSearchParams();
  const query = params.get("q") ?? "";
  const [text, setText] = useState(query);
  const [results, setResults] = useState<SearchResults | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [allSongs, setAllSongs] = useState(false);
  const input = useRef<HTMLInputElement>(null);

  useEffect(() => { input.current?.focus(); }, []);

  // Searches as you type, after a pause.
  useEffect(() => {
    const handle = setTimeout(() => {
      if (text.trim() !== query) setParams(text.trim() ? { q: text.trim() } : {}, { replace: true });
    }, 450);
    return () => clearTimeout(handle);
  }, [text, query, setParams]);

  useEffect(() => {
    setAllSongs(false);
    if (!query) { setResults(null); return; }
    let cancelled = false;
    setLoading(true);
    setError(null);
    api.search(query).then(
      (r) => { if (!cancelled) { setResults(r); setLoading(false); } },
      (e) => { if (!cancelled) { setError(e.message); setLoading(false); } },
    );
    return () => { cancelled = true; };
  }, [query]);

  const songs = results?.songs ?? [];
  const shown = allSongs ? songs : songs.slice(0, SONGS_AT_FIRST);
  const nothing = results && !results.top && !songs.length && !results.artists.length && !results.albums.length;

  return (
    <div className="page">
      <form className="search-box" onSubmit={(e) => { e.preventDefault(); setParams(text.trim() ? { q: text.trim() } : {}); }}>
        <SearchIcon size={20} aria-hidden />
        <input ref={input} type="search" placeholder="Artists, albums, songs…" value={text} onChange={(e) => setText(e.target.value)} aria-label="Search" />
        {loading && <Spinner size={18} />}
      </form>

      {error && <div className="notice notice-error">{error}</div>}
      {!query && <Empty icon={<SearchIcon size={40} />} title="Search all your music">Artists, albums and songs from your library and every service you can use.</Empty>}
      {nothing && <Empty title={`Nothing found for “${results.query}”`}>Try other words, or check your sources in Settings.</Empty>}

      {results && (results.top || songs.length > 0) && (
        <div className="search-top">
          {results.top && (
            <section>
              <h2 className="section-title">Top result</h2>
              <TopCard top={results.top} songs={songs} />
            </section>
          )}
          {songs.length > 0 && (
            <section className="search-songs">
              <h2 className="section-title">Songs</h2>
              <TrackList rows={shown.map((t) => ({ key: trackKey(t), track: t }))} />
              {songs.length > SONGS_AT_FIRST && (
                <Button variant="ghost" onClick={() => setAllSongs((a) => !a)}>{allSongs ? "Fewer songs" : `All ${songs.length} songs`}</Button>
              )}
            </section>
          )}
        </div>
      )}

      {results && results.artists.length > 0 && (
        <section>
          <h2 className="section-title">Artists</h2>
          <CardRow>{results.artists.map((a) => <ArtistCard key={a.id} artist={a} />)}</CardRow>
        </section>
      )}
      {results && results.albums.length > 0 && (
        <section>
          <h2 className="section-title">Albums</h2>
          <CardRow>{results.albums.map((a) => <AlbumCard key={a.id} album={a} />)}</CardRow>
        </section>
      )}
    </div>
  );
}

/** The likeliest match, large: opens the artist or album, or plays the song. */
function TopCard({ top, songs }: { top: TopResult; songs: Track[] }) {
  const navigate = useNavigate();
  const [starting, setStarting] = useState(false);
  const title = top.artist?.name ?? top.album?.title ?? top.track?.title ?? "";
  const art = top.artist?.artworkUrl ?? top.album?.artworkUrl ?? top.track?.artworkUrl;
  const kind = top.kind === "artist" ? "Artist" : top.kind === "album" ? `Album · ${top.album?.artist}` : `Song · ${top.track?.artist}`;

  const open = () => {
    if (top.artist) navigate(artistLink(top.artist));
    else if (top.album) navigate(albumLink(top.album));
    else if (top.track) play();
  };
  const play = async () => {
    if (top.track) {
      const index = songs.findIndex((s) => trackKey(s) === trackKey(top.track!));
      if (index >= 0) player.playTracks(songs, index); else player.playTracks([top.track]);
      return;
    }
    setStarting(true);
    try {
      const tracks = top.artist ? (await api.artist(top.artist.id)).topSongs : (await api.album(top.album!.id)).tracks;
      if (tracks.length) player.playTracks(tracks);
    } catch (e) {
      errorToast(e);
    } finally {
      setStarting(false);
    }
  };

  return (
    <div className="top-card" role="link" tabIndex={0} onClick={open} onKeyDown={(e) => { if (e.key === "Enter") open(); }}>
      <Artwork url={art} size={112} className={top.kind === "artist" ? "round" : ""} />
      <h3>{title}</h3>
      <div className="card-sub"><span className="badge">{kind}</span></div>
      <button
        className="top-play"
        aria-label={`Play ${title}`}
        disabled={starting}
        onClick={(e) => { e.stopPropagation(); void play(); }}
      >
        {starting ? <Spinner size={20} /> : <Play size={24} fill="currentColor" />}
      </button>
    </div>
  );
}
