import { useState } from "react";
import { Music } from "lucide-react";
import { art } from "../lib/api";

/** A song's or playlist's artwork, with a gentle placeholder when there's none (or it fails). */
export function Artwork({ url, size, px, className = "", alt = "" }: { url?: string; size: number; px?: number; className?: string; alt?: string }) {
  const [failed, setFailed] = useState<string | null>(null);
  const src = art(url, px ?? Math.min(1024, Math.round(size * (window.devicePixelRatio || 1))));
  const show = src && failed !== src;
  return (
    <div className={`artwork ${className}`} style={{ width: size, height: size }}>
      {show ? (
        <img src={src} alt={alt} loading="lazy" decoding="async" onError={() => setFailed(src)} />
      ) : (
        <Music size={Math.max(16, size * 0.36)} aria-hidden />
      )}
    </div>
  );
}

/** Up to four covers in a square, for a playlist. */
export function Mosaic({ urls, size }: { urls: string[]; size: number }) {
  if (urls.length < 4) return <Artwork url={urls[0]} size={size} />;
  const half = size / 2;
  return (
    <div className="artwork mosaic" style={{ width: size, height: size }}>
      {urls.slice(0, 4).map((u, i) => <Artwork key={i} url={u} size={half} />)}
    </div>
  );
}
