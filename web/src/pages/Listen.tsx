import { useEffect, useRef, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { AudioLines, ExternalLink, Play, SkipForward, X } from "lucide-react";
import { api } from "../lib/api";
import type { Heard, ListenResult, Track } from "../lib/types";
import { player } from "../player/player";
import { Artwork } from "../components/Artwork";
import { TrackMenu } from "../components/TrackList";
import { Button, IconButton, PageHeader, errorToast, useLoad } from "../components/ui";

/** When each try is sent (seconds of recording); the last one may also ask AudD. */
const TRIES = [4, 7, 10];

type Phase = "idle" | "listening" | "found" | "missed";

/**
 * Listen: what's playing around you. The microphone records for up to ten seconds; every few
 * seconds what's been heard so far goes to the server, which looks for it in your library (and,
 * last, AudD if you've added it), stopping as soon as it's sure.
 */
export function Listen() {
  const [params, setParams] = useSearchParams();
  const status = useLoad(() => api.listenStatus(), []);
  const [phase, setPhase] = useState<Phase>("idle");
  const [result, setResult] = useState<ListenResult | null>(null);
  const [heardAt, setHeardAt] = useState(0);
  const [seconds, setSeconds] = useState(0);
  const [refresh, setRefresh] = useState(0);
  const heard = useLoad(() => api.listenHeard(), [refresh]);
  const session = useRef<{ stop: () => void } | null>(null);

  const stop = () => {
    session.current?.stop();
    session.current = null;
  };
  useEffect(() => stop, []);

  const start = async () => {
    stop();
    setResult(null);
    if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === "undefined") {
      setPhase("missed");
      setResult({ found: false, message: "Listening needs the microphone, which browsers only allow on pages opened over HTTPS." });
      return;
    }
    let stream: MediaStream;
    try {
      // The music as it is: no echo cancelling, noise suppression or levelling, which are made for voices.
      stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false, channelCount: 1 } });
    } catch {
      setPhase("missed");
      setResult({ found: false, message: "The browser wasn't allowed to use the microphone. Allow it for this site, then try again." });
      return;
    }
    const type = ["audio/webm;codecs=opus", "audio/ogg;codecs=opus", "audio/mp4", "audio/webm"].find((t) => MediaRecorder.isTypeSupported(t)) ?? "";
    const recorder = new MediaRecorder(stream, type ? { mimeType: type, audioBitsPerSecond: 64_000 } : undefined);
    const chunks: Blob[] = [];
    recorder.ondataavailable = (e) => { if (e.data.size) chunks.push(e.data); };
    let cancelled = false;
    const timers: number[] = [];
    const finish = () => {
      timers.forEach((t) => clearTimeout(t));
      if (recorder.state !== "inactive") recorder.stop();
      stream.getTracks().forEach((t) => t.stop());
    };
    session.current = { stop: () => { cancelled = true; finish(); } };
    recorder.start(250);
    const began = Date.now();
    setPhase("listening");
    setSeconds(0);
    const tick = window.setInterval(() => {
      if (cancelled) return clearInterval(tick);
      setSeconds(Math.min(TRIES[TRIES.length - 1], Math.floor((Date.now() - began) / 1000)));
    }, 250);
    timers.push(tick);

    let candidate: string | undefined;
    for (const [i, at] of TRIES.entries()) {
      await new Promise((resolve) => timers.push(window.setTimeout(resolve, Math.max(0, began + at * 1000 - Date.now()))));
      if (cancelled) return;
      const last = i === TRIES.length - 1;
      if (last) finish();
      const recorded = Date.now();
      const clip = new Blob(chunks, { type: recorder.mimeType || type || "audio/webm" });
      let answer: ListenResult;
      try {
        answer = await api.listen(clip, last, candidate);
      } catch (e) {
        if (!last) continue;
        errorToast(e);
        finish();
        setPhase("idle");
        return;
      }
      if (cancelled) return;
      if (answer.found) {
        finish();
        setResult(answer);
        setHeardAt(recorded);
        setPhase("found");
        setRefresh((n) => n + 1);
        return;
      }
      candidate = answer.candidate ?? candidate;
      if (last) {
        setResult(answer);
        setPhase("missed");
      }
    }
  };

  // Opened from the search box's Listen button: starts straight away.
  useEffect(() => {
    if (params.get("start") === "1") {
      setParams({}, { replace: true });
      void start();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const s = status.data;
  const learning = s && s.libraries && s.enabled && s.total > 0 && s.learned < s.total;
  return (
    <div className="page listen-page">
      <PageHeader title="Listen" subtitle="What's playing around you? Hold your device near the music." />
      <section className="listen-hero">
        <button
          className={`listen-button ${phase === "listening" ? "is-listening" : ""}`}
          onClick={() => (phase === "listening" ? (stop(), setPhase("idle")) : void start())}
          aria-label={phase === "listening" ? "Stop listening" : "Listen"}
        >
          <span className="listen-ring" aria-hidden />
          <span className="listen-ring listen-ring-2" aria-hidden />
          <AudioLines size={64} />
        </button>
        <p className="listen-state" aria-live="polite">
          {phase === "listening" ? `Listening… ${seconds}s` : phase === "idle" ? "Tap to listen" : phase === "found" ? "Got it" : "Tap to try again"}
        </p>
        {phase === "found" && result && <Found result={result} heardAt={heardAt} />}
        {phase === "missed" && result && (
          <div className="listen-missed">
            <p className="listen-missed-title">Couldn't tell what that was</p>
            {result.message && <p>{result.message}</p>}
          </div>
        )}
        {s && (
          <p className="listen-status">
            {!s.libraries ? "No library to learn yet: add your Jellyfin in Settings › Sources."
              : !s.enabled ? "Learning your library is turned off (Admin › Settings)."
              : learning ? `Learning your library: ${s.learned.toLocaleString()} of ${s.total.toLocaleString()} songs so far`
              : `Knows all ${s.learned.toLocaleString()} songs in your library`}
            {" · "}
            {s.audd ? "AudD for everything else" : "Add AudD in Settings › Sources to recognise any song"}
          </p>
        )}
      </section>
      <RecentlyHeard items={heard.data ?? []} onForget={async (id) => { try { await api.forgetHeard(id); setRefresh((n) => n + 1); } catch (e) { errorToast(e); } }} />
    </div>
  );
}

/** What was recognised: its cover and name, and ways to play it (from where it is in the room, too). */
function Found({ result, heardAt }: { result: ListenResult; heardAt: number }) {
  const song = result.song;
  const playFromHere = () => {
    if (!song || result.positionMs == null) return;
    // Where the song is now: where it was when recording stopped, and the time since.
    const at = (result.positionMs + Date.now() - heardAt) / 1000;
    const length = (song.durationMs ?? 0) / 1000;
    player.takeOver([song], 0, length > 0 ? Math.min(at, length - 1) : at, true);
  };
  return (
    <div className="listen-found">
      <Artwork url={result.artworkUrl ?? song?.artworkUrl} size={200} px={512} alt="" className="listen-found-art" />
      <div className="listen-found-text">
        <h2>{result.title}</h2>
        <p className="listen-found-artist">{result.artist}{result.album ? ` · ${result.album}` : ""}</p>
        <p className="listen-found-via">{result.via === "library" ? "From your library" : "Recognised by AudD"}</p>
        <div className="listen-found-actions">
          {song ? (
            <>
              <Button variant="primary" onClick={() => player.playTracks([song])}><Play size={18} /> Play</Button>
              {result.positionMs != null && <Button onClick={playFromHere}><SkipForward size={18} /> Play from here</Button>}
              <TrackMenu track={song} />
            </>
          ) : (
            <p className="listen-found-note">It isn't on any of your sources.</p>
          )}
          {result.link && <a className="btn btn-ghost" href={result.link} target="_blank" rel="noreferrer noopener"><ExternalLink size={16} /> More</a>}
        </div>
      </div>
    </div>
  );
}

function RecentlyHeard({ items, onForget }: { items: Heard[]; onForget: (id: string) => void }) {
  if (!items.length) return null;
  return (
    <section className="listen-recent">
      <h2 className="section-title">Recently heard</h2>
      <ol className="listen-recent-list">
        {items.map((h) => (
          <li key={h.id} className="listen-recent-row">
            <Artwork url={h.artworkUrl ?? h.song?.artworkUrl} size={52} px={128} alt="" />
            <div className="listen-recent-text">
              <span className="listen-recent-title">{h.title}</span>
              <span className="listen-recent-artist">{h.artist} · {when(h.at)}</span>
            </div>
            {h.song && <IconButton label={`Play ${h.title}`} onClick={() => player.playTracks([h.song as Track])}><Play size={18} /></IconButton>}
            {h.song && <TrackMenu track={h.song} />}
            <IconButton label="Remove" onClick={() => onForget(h.id)}><X size={18} /></IconButton>
          </li>
        ))}
      </ol>
    </section>
  );
}

/** "Just now", "12 min ago", "Yesterday", or the date. */
function when(at: number): string {
  const minutes = Math.round((Date.now() - at) / 60_000);
  if (minutes < 1) return "Just now";
  if (minutes < 60) return `${minutes} min ago`;
  if (minutes < 24 * 60) return `${Math.round(minutes / 60)} h ago`;
  if (minutes < 48 * 60) return "Yesterday";
  return new Date(at).toLocaleDateString(undefined, { day: "numeric", month: "short", year: new Date(at).getFullYear() === new Date().getFullYear() ? undefined : "numeric" });
}
