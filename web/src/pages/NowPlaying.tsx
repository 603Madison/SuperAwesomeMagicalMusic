import { useEffect, useRef, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { api } from "../lib/api";
import { SOURCE_LABELS } from "../lib/format";
import type { LyricsView, Track } from "../lib/types";
import { usePlayer } from "../player/player";
import { Artwork } from "../components/Artwork";
import { Queue } from "../components/Queue";
import { TrackMenu } from "../components/TrackList";
import { Empty, Spinner } from "../components/ui";

export function NowPlaying() {
  const item = usePlayer((s) => s.queue[s.index]);
  const current = usePlayer((s) => s.current);
  const buffering = usePlayer((s) => s.buffering);
  const [params, setParams] = useSearchParams();
  const tab = params.get("lyrics") ? "lyrics" : "queue";
  const liveNow = usePlayer((s) => s.liveNow);
  const base = current?.track ?? item?.track;
  const track = base && liveNow ? { ...base, title: liveNow.title || base.title, artist: liveNow.artist || base.title, artworkUrl: liveNow.artworkUrl ?? base.artworkUrl, album: base.title } : base;

  if (!track) return <Empty title="Nothing playing">Pick a song to play from search or your playlists.</Empty>;
  const queued = item?.track;
  const differentCopy = queued && current && (queued.source !== current.track.source || queued.sourceId !== current.track.sourceId);

  return (
    <div className="now-playing">
      <section className="now-art">
        <Artwork url={track.artworkUrl} size={360} px={1024} className="now-artwork" />
        <div className="now-text">
          <h1>{track.title}</h1>
          <p className="now-artist">{track.artist}</p>
          {track.album && <p className="now-album">{track.album}</p>}
          <p className="now-detail">
            {current ? `${current.description} · ${SOURCE_LABELS[current.track.source]}` : buffering ? "Finding the best copy…" : ""}
            {differentCopy ? ` (asked for ${SOURCE_LABELS[queued!.source]})` : ""}
          </p>
          {current?.live ? (liveNow?.song && <TrackMenu track={liveNow.song} />) : <TrackMenu track={track} />}
        </div>
      </section>
      <section className="now-side">
        <div className="tabs" role="tablist">
          <button role="tab" aria-selected={tab === "queue"} className={tab === "queue" ? "is-active" : ""} onClick={() => setParams({})}>Queue</button>
          <button role="tab" aria-selected={tab === "lyrics"} className={tab === "lyrics" ? "is-active" : ""} onClick={() => setParams({ lyrics: "1" })}>Lyrics</button>
        </div>
        {tab === "queue" ? <Queue /> : current?.live ? (liveNow?.song ? <Lyrics track={liveNow.song} /> : <Empty title="No lyrics for live radio">When a channel says which song is on, its lyrics show here.</Empty>) : <Lyrics track={queued ?? track} />}
      </section>
    </div>
  );
}

function Lyrics({ track }: { track: Track }) {
  const [lyrics, setLyrics] = useState<LyricsView | null | undefined>(undefined);
  const position = usePlayer((s) => s.position);
  const list = useRef<HTMLOListElement>(null);
  const key = `${track.via}|${track.source}|${track.sourceId}`;

  useEffect(() => {
    let cancelled = false;
    setLyrics(undefined);
    api.lyrics(track).then((l) => !cancelled && setLyrics(l), () => !cancelled && setLyrics(null));
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  const lines = lyrics?.lines ?? [];
  const ms = position * 1000;
  const currentLine = lyrics?.synced ? lines.findLastIndex((l) => l.timeMs != null && l.timeMs <= ms) : -1;

  useEffect(() => {
    if (currentLine < 0) return;
    list.current?.children[currentLine]?.scrollIntoView({ block: "center", behavior: "smooth" });
  }, [currentLine]);

  if (lyrics === undefined) return <div className="center-pad"><Spinner /></div>;
  if (!lines.length) return <Empty title="No lyrics found">Neither your library nor LRCLIB has lyrics for this song.</Empty>;
  return (
    <div className="lyrics">
      <ol ref={list} className={lyrics?.synced ? "is-synced" : ""}>
        {lines.map((line, i) => (
          <li key={i} className={i === currentLine ? "is-current" : i < currentLine ? "is-past" : ""}>{line.text || "♪"}</li>
        ))}
      </ol>
      <p className="lyrics-source">Lyrics from {lyrics?.source}</p>
    </div>
  );
}
