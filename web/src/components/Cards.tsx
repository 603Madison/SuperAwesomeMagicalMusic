import { Link } from "react-router-dom";
import type { AlbumSummary, ArtistSummary } from "../lib/types";
import { Artwork } from "./Artwork";

export const artistLink = (artist: ArtistSummary) => `/artist?id=${encodeURIComponent(artist.id)}`;
export const artistNamed = (name: string) => `/artist?name=${encodeURIComponent(name)}`;
export const albumLink = (album: AlbumSummary) => `/album?id=${encodeURIComponent(album.id)}`;
export const albumNamed = (title: string, artist: string) => `/album?title=${encodeURIComponent(title)}&artist=${encodeURIComponent(artist)}`;

export function ArtistCard({ artist }: { artist: ArtistSummary }) {
  return (
    <Link to={artistLink(artist)} className="card artist-card">
      <Artwork url={artist.artworkUrl} size={160} className="round" />
      <div className="card-title">{artist.name}</div>
      <div className="card-sub">Artist</div>
    </Link>
  );
}

export function AlbumCard({ album, showArtist = true }: { album: AlbumSummary; showArtist?: boolean }) {
  const sub = [album.year, showArtist ? album.artist : null].filter(Boolean).join(" · ");
  return (
    <Link to={albumLink(album)} className="card">
      <Artwork url={album.artworkUrl} size={160} />
      <div className="card-title" title={album.title}>{album.title}</div>
      <div className="card-sub">{sub || "Album"}</div>
    </Link>
  );
}

/** A row of cards that scrolls sideways. */
export function CardRow({ children }: { children: React.ReactNode }) {
  return <div className="card-row">{children}</div>;
}
