import { useEffect, useState } from "react";
import type { Track } from "../lib/types";
import { player, usePlayer } from "../player/player";
import { usePrefs } from "../player/prefs";
import { Button, Modal } from "./ui";
import { duration, trackKey } from "../lib/format";

const SOURCES: Record<string, string> = { JELLYFIN: "Jellyfin", YOUTUBE_MUSIC: "YouTube Music", QOBUZ: "Qobuz", SIRIUSXM: "SiriusXM", RADIO: "Radio station" };

function sourceOf(track: Track): string {
  if (track.source === "SIRIUSXM" && track.sourceId.startsWith("channel:")) return "SiriusXM live channel";
  if (track.source === "SIRIUSXM" && track.sourceId.startsWith("stationtrack:")) return "Pandora station, through SiriusXM";
  return SOURCES[track.source] ?? track.source;
}

/**
 * Where a song comes from and, while it plays, exactly what's playing: the copy your server picked,
 * its format and bitrate, the quality cap, volume levelling and how much is loaded.
 */
export function TrackInfo({ track, onClose }: { track: Track; onClose: () => void }) {
  const current = usePlayer((s) => s.current);
  const liveNow = usePlayer((s) => s.liveNow);
  const kbps = usePrefs((s) => s.kbps);
  const levelling = usePrefs((s) => s.levelling);
  const isCurrent = !!current && trackKey(current.track) === trackKey(track);
  const [ahead, setAhead] = useState(0);
  const [gain, setGain] = useState(0);
  useEffect(() => {
    if (!isCurrent) return;
    const tick = () => { setAhead(player.bufferedAhead()); setGain(player.levellingGainDb); };
    tick();
    const timer = window.setInterval(tick, 1000);
    return () => window.clearInterval(timer);
  }, [isCurrent]);
  const live = !!current?.live;
  const rows: [string, string][] = [];
  if (!(isCurrent && live)) rows.push(["Artist", track.artist]);
  if (track.album) rows.push(["Album", track.album]);
  if (track.durationMs) rows.push(["Length", duration(track.durationMs)]);
  rows.push(["From", sourceOf(track)], [`${SOURCES[track.source] ?? track.source} id`, track.sourceId]);
  const now: [string, string][] = [];
  if (isCurrent && current) {
    now.push(["Copy", current.description]);
    if (live && liveNow?.title) now.push(["On now", [liveNow.title, liveNow.artist].filter(Boolean).join(" · ")]);
    if (live) now.push(["Stream", current.hls ? "Live stream (HLS), through your server" : "Live stream, through your server"]);
    if (current.kbps) now.push(["Bitrate", `${current.kbps} kbps${current.transcoded ? " (converted on your server)" : ""}`]);
    if (current.extension) now.push(["Format", current.extension.toUpperCase()]);
    if (!live) now.push(["Quality cap", kbps ? `${kbps} kbps` : "Original"]);
    now.push(["Volume levelling", !levelling ? "Off" : `${gain >= 0 ? "+" : ""}${gain.toFixed(1)} dB (${current.gainDb != null ? "from your library's loudness value" : "measured while playing"})`]);
    if (!live) now.push(["Loaded ahead", duration(ahead * 1000)]);
  }
  return (
    <Modal title={track.title} onClose={onClose} footer={<Button variant="primary" onClick={onClose}>Close</Button>}>
      <dl className="info-list">
        {rows.map(([k, v]) => <div key={k}><dt>{k}</dt><dd>{v}</dd></div>)}
      </dl>
      <h3 className="info-heading">{isCurrent ? "Playing now" : "Not playing"}</h3>
      {isCurrent ? (
        <dl className="info-list">
          {now.map(([k, v]) => <div key={k}><dt>{k}</dt><dd>{v}</dd></div>)}
        </dl>
      ) : (
        <p className="muted">When it plays, your server picks the copy: your library first, then the other services, for the best one within your quality setting.</p>
      )}
    </Modal>
  );
}
