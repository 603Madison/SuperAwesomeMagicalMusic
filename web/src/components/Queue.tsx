import { ChevronDown, ChevronUp, X } from "lucide-react";
import { player, usePlayer } from "../player/player";
import { Artwork } from "./Artwork";
import { Button, Empty, IconButton } from "./ui";

/** What's playing and what's next, with ways to change the order. */
export function Queue({ onClose }: { onClose?: () => void }) {
  const queue = usePlayer((s) => s.queue);
  const index = usePlayer((s) => s.index);
  const upcoming = queue.slice(index + 1);
  const current = queue[index];

  return (
    <div className="queue">
      <div className="queue-head">
        <h2>Queue</h2>
        <div className="queue-actions">
          {upcoming.length > 0 && <Button variant="ghost" onClick={() => player.clearUpcoming()}>Clear</Button>}
          {onClose && <IconButton label="Close the queue" onClick={onClose}><X size={20} /></IconButton>}
        </div>
      </div>
      {!current && <Empty title="The queue is empty">Play something from search or a playlist.</Empty>}
      {current && (
        <>
          <h3 className="queue-section">Now playing</h3>
          <QueueRow index={index} />
        </>
      )}
      {upcoming.length > 0 && <h3 className="queue-section">Next up · {upcoming.length}</h3>}
      <ol className="queue-list">
        {upcoming.map((item, i) => <QueueRow key={item.id} index={index + 1 + i} />)}
      </ol>
    </div>
  );
}

function QueueRow({ index }: { index: number }) {
  const item = usePlayer((s) => s.queue[index]);
  const isCurrent = usePlayer((s) => s.index === index);
  const count = usePlayer((s) => s.queue.length);
  if (!item) return null;
  return (
    <li className={`queue-row ${isCurrent ? "is-current" : ""}`}>
      <button className="queue-main" onClick={() => player.jumpTo(index)} aria-label={`Play ${item.track.title}`}>
        <Artwork url={item.track.artworkUrl} size={40} />
        <span className="queue-text">
          <span className="track-title">{item.track.title}</span>
          <span className="track-sub">{item.track.artist}</span>
        </span>
      </button>
      {!isCurrent && (
        <span className="queue-row-actions">
          <IconButton label="Move up" disabled={index <= 0} onClick={() => player.move(index, index - 1)}><ChevronUp size={16} /></IconButton>
          <IconButton label="Move down" disabled={index >= count - 1} onClick={() => player.move(index, index + 1)}><ChevronDown size={16} /></IconButton>
          <IconButton label="Remove from the queue" onClick={() => player.remove(index)}><X size={16} /></IconButton>
        </span>
      )}
    </li>
  );
}
