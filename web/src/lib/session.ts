import { create } from "zustand";
import { api, ApiError, setSignedOutHandler } from "./api";
import type { AuthConfig, PlaylistSummary, Track, User } from "./types";

interface Session {
  config: AuthConfig | null;
  user: User | null;
  /** Still finding out whether someone is signed in. */
  loading: boolean;
  /** The person's playlists, for the sidebar and "add to playlist". */
  playlists: PlaylistSummary[];
  /** Their favourite live channels and stations. */
  favorites: Track[];
  /** Their top channels and stations (favourites played lately first), for the sidebar. */
  topStations: Track[];
}

export const useSession = create<Session>(() => ({ config: null, user: null, loading: true, playlists: [], favorites: [], topStations: [] }));

/** Identifies a live channel or station, on the source it's from. */
export const liveKey = (track: Track) => `${track.via ?? ""}|${track.source}:${track.sourceId}`;

export const session = {
  async start() {
    const [config, me] = await Promise.all([
      api.authConfig().catch(() => null),
      api.me().catch((e) => (e instanceof ApiError && e.status === 401 ? null : Promise.reject(e))).catch(() => null),
    ]);
    useSession.setState({ config, user: me?.user ?? null, loading: false });
    if (me) {
      void session.refreshPlaylists();
      void session.refreshFavorites();
      void session.refreshTopStations();
    }
  },

  signedIn(user: User) {
    useSession.setState({ user });
    void session.refreshPlaylists();
    void session.refreshFavorites();
    void session.refreshTopStations();
  },

  async signOut() {
    await api.logout().catch(() => undefined);
    useSession.setState({ user: null, playlists: [], favorites: [], topStations: [] });
  },

  async refreshPlaylists() {
    try {
      useSession.setState({ playlists: await api.playlists() });
    } catch {
      // Shown as empty; the page itself reports errors.
    }
  },

  async refreshFavorites() {
    try {
      useSession.setState({ favorites: await api.liveFavorites() });
    } catch {
      // None shown; nothing else depends on them.
    }
  },

  async refreshTopStations() {
    try {
      useSession.setState({ topStations: await api.liveTop(4) });
    } catch {
      // None shown.
    }
  },

  /** A playlist started playing: it moves to the top of the sidebar, here and on other devices. */
  playlistPlayed(id: string) {
    const now = Date.now();
    useSession.setState({ playlists: useSession.getState().playlists.map((p) => (p.id === id ? { ...p, playedAt: now } : p)) });
    api.playlistPlayed(id).catch(() => undefined);
  },

  /** A channel, station or Pandora station started playing: noted for the sidebar's top ones. */
  livePlayed(track: Track) {
    api.livePlayed(track).then(() => session.refreshTopStations(), () => undefined);
  },

  /** Makes a live channel or station a favourite, or not; shown at once, then as the server has it. */
  async setFavorite(track: Track, favorite: boolean) {
    const before = useSession.getState().favorites;
    const others = before.filter((t) => liveKey(t) !== liveKey(track));
    useSession.setState({ favorites: favorite ? [track, ...others] : others });
    try {
      useSession.setState({ favorites: await api.setLiveFavorite(track, favorite) });
      void session.refreshTopStations();
    } catch (e) {
      useSession.setState({ favorites: before });
      throw e;
    }
  },

  setUser(user: User) {
    useSession.setState({ user });
  },
};

setSignedOutHandler(() => useSession.setState({ user: null }));
