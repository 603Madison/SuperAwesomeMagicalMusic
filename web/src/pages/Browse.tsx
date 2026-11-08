import { useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { ListPlus, Plus } from "lucide-react";
import { api } from "../lib/api";
import { duration, plural, trackKey } from "../lib/format";
import { session, useSession } from "../lib/session";
import type { Track } from "../lib/types";
import { Artwork } from "../components/Artwork";
import { AlbumCard, CardRow } from "../components/Cards";
import { NewPlaylistDialog, PlayButtons, TrackList } from "../components/TrackList";
import { Empty, errorToast, Menu, Spinner, toast, useLoad } from "../components/ui";

/** An artist: their best-known songs, then their albums and singles, from all your sources. */
export function ArtistView() {
  const [params] = useSearchParams();
  const id = params.get("id");
  const name = params.get("name");
  const { data, error, loading } = useLoad(() => (id ? api.artist(id) : api.artistNamed(name ?? "")), [id, name]);

  if (loading && !data) return <div className="center-pad"><Spinner /></div>;
  if (error || !data) return <Empty title="Couldn't open this artist">{error}</Empty>;
  const { artist, topSongs, albums, singles } = data;

  return (
    <div className="page">
      <div className="playlist-hero artist-hero">
        <Artwork url={artist.artworkUrl} size={200} px={512} className="round" />
        <div className="playlist-hero-text">
          <span className="eyebrow">Artist</span>
          <h1>{artist.name}</h1>
          <PlayButtons tracks={topSongs}><AddAll tracks={topSongs} /></PlayButtons>
        </div>
      </div>
      {topSongs.length > 0 && (
        <section>
          <h2 className="section-title">Popular</h2>
          <TrackList rows={topSongs.map((t) => ({ key: trackKey(t), track: t }))} />
        </section>
      )}
      {albums.length > 0 && (
        <section>
          <h2 className="section-title">Albums</h2>
          <CardRow>{albums.map((a) => <AlbumCard key={a.id} album={a} showArtist={false} />)}</CardRow>
        </section>
      )}
      {singles.length > 0 && (
        <section>
          <h2 className="section-title">Singles and EPs</h2>
          <CardRow>{singles.map((a) => <AlbumCard key={a.id} album={a} showArtist={false} />)}</CardRow>
        </section>
      )}
      {!topSongs.length && !albums.length && !singles.length && <Empty title="Nothing by them here yet" />}
    </div>
  );
}

/** An album's songs, in order, from your library when it has them. */
export function AlbumView() {
  const [params] = useSearchParams();
  const id = params.get("id");
  const title = params.get("title");
  const artistName = params.get("artist");
  const { data, error, loading } = useLoad(() => (id ? api.album(id) : api.albumNamed(title ?? "", artistName ?? "")), [id, title, artistName]);

  if (loading && !data) return <div className="center-pad"><Spinner /></div>;
  if (error || !data) return <Empty title="Couldn't open this album">{error}</Empty>;
  const { album, tracks } = data;
  const total = tracks.reduce((sum, t) => sum + (t.durationMs ?? 0), 0);
  const details = [album.year, plural(tracks.length, "song"), total ? duration(total) : null].filter(Boolean).join(" · ");

  return (
    <div className="page">
      <div className="playlist-hero">
        <Artwork url={album.artworkUrl} size={200} px={512} />
        <div className="playlist-hero-text">
          <span className="eyebrow">Album</span>
          <h1>{album.title}</h1>
          <div className="page-subtitle">
            <Link to={`/artist?id=${encodeURIComponent(album.artistId)}`} className="hero-link">{album.artist}</Link>
            {details && <span> · {details}</span>}
          </div>
          <PlayButtons tracks={tracks}><AddAll tracks={tracks} /></PlayButtons>
        </div>
      </div>
      <TrackList rows={tracks.map((t, i) => ({ key: `${i}:${trackKey(t)}`, track: t }))} numbered showAlbum={false} />
    </div>
  );
}

/** Adds a whole list of songs to a playlist. */
function AddAll({ tracks }: { tracks: Track[] }) {
  const playlists = useSession((s) => s.playlists);
  const [creating, setCreating] = useState(false);
  if (!tracks.length) return null;
  const add = async (id: string, name: string) => {
    try {
      await api.addToPlaylist(id, tracks);
      toast(`Added ${plural(tracks.length, "song")} to ${name}`);
      void session.refreshPlaylists();
    } catch (e) {
      errorToast(e);
    }
  };
  return (
    <>
      <Menu
        label="Add to a playlist"
        buttonClass="btn btn-tonal"
        trigger={<><ListPlus size={18} /> Add to playlist</>}
        items={[
          { label: "New playlist…", icon: <Plus size={18} />, onSelect: () => setCreating(true) },
          ...playlists.map((p) => ({ label: p.name, onSelect: () => void add(p.id, p.name) })),
        ]}
      />
      {creating && <NewPlaylistDialog tracks={tracks} onClose={() => setCreating(false)} />}
    </>
  );
}
