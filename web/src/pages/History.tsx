import { useState } from "react";
import { Link } from "react-router-dom";
import { ChevronRight, History as HistoryIcon, Sparkles } from "lucide-react";
import { api } from "../lib/api";
import { when } from "../lib/format";
import type { Listen } from "../lib/types";
import { TrackList } from "../components/TrackList";
import { Button, Empty, errorToast, Modal, PageHeader, Spinner, useLoad } from "../components/ui";

export function History() {
  const { data, error, loading, setData } = useLoad(() => api.history(), []);
  const [more, setMore] = useState(false);
  const [confirm, setConfirm] = useState(false);
  const listens = data ?? [];

  const loadMore = async () => {
    setMore(true);
    try {
      const older = await api.history(listens[listens.length - 1]?.playedAt);
      setData([...listens, ...older]);
    } catch (e) {
      errorToast(e);
    }
    setMore(false);
  };

  // Grouped by day.
  const days: { label: string; listens: Listen[] }[] = [];
  for (const listen of listens) {
    const label = dayLabel(listen.playedAt);
    if (days[days.length - 1]?.label !== label) days.push({ label, listens: [] });
    days[days.length - 1].listens.push(listen);
  }

  return (
    <div className="page">
      <PageHeader title="History" subtitle="What you've listened to, on every device" actions={listens.length > 0 && <Button variant="ghost" onClick={() => setConfirm(true)}>Clear history</Button>} />
      {listens.length > 0 && (
        <Link to="/wrapped" className="wrapped-banner">
          <Sparkles size={22} />
          <span><strong>Your {new Date().getFullYear()} Wrapped</strong><span>Top songs, artists and stations, and your year's story</span></span>
          <ChevronRight size={20} />
        </Link>
      )}
      {loading && !data && <div className="center-pad"><Spinner /></div>}
      {error && <div className="notice notice-error">{error}</div>}
      {data && !listens.length && <Empty icon={<HistoryIcon size={40} />} title="Nothing yet">Songs show up here once you've listened to a good part of them.</Empty>}
      {days.map((day) => (
        <section key={day.label}>
          <h2 className="section-title">{day.label}</h2>
          <TrackList rows={day.listens.map((l) => ({ key: `${l.playedAt}-${l.track.sourceId}`, track: l.track }))} />
        </section>
      ))}
      {listens.length >= 100 && listens.length % 100 === 0 && (
        <div className="center-pad"><Button busy={more} onClick={loadMore}>Show older</Button></div>
      )}
      {confirm && (
        <Modal title="Clear your history?" onClose={() => setConfirm(false)} footer={<>
          <Button variant="ghost" onClick={() => setConfirm(false)}>Cancel</Button>
          <Button variant="danger" onClick={async () => { try { await api.clearHistory(); setData([]); setConfirm(false); } catch (e) { errorToast(e); } }}>Clear</Button>
        </>}>
          <p>Everything you've listened to is forgotten, on every device.</p>
        </Modal>
      )}
    </div>
  );
}

function dayLabel(time: number): string {
  const date = new Date(time);
  const today = new Date();
  const yesterday = new Date(Date.now() - 86_400_000);
  if (date.toDateString() === today.toDateString()) return "Today";
  if (date.toDateString() === yesterday.toDateString()) return "Yesterday";
  return when(time);
}
