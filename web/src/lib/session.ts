import { create } from "zustand";
import { api, ApiError, setSignedOutHandler } from "./api";
import type { AuthConfig, PlaylistSummary, User } from "./types";

interface Session {
  config: AuthConfig | null;
  user: User | null;
  /** Still finding out whether someone is signed in. */
  loading: boolean;
  /** The person's playlists, for the sidebar and "add to playlist". */
  playlists: PlaylistSummary[];
}

export const useSession = create<Session>(() => ({ config: null, user: null, loading: true, playlists: [] }));

export const session = {
  async start() {
    const [config, me] = await Promise.all([
      api.authConfig().catch(() => null),
      api.me().catch((e) => (e instanceof ApiError && e.status === 401 ? null : Promise.reject(e))).catch(() => null),
    ]);
    useSession.setState({ config, user: me?.user ?? null, loading: false });
    if (me) void session.refreshPlaylists();
  },

  signedIn(user: User) {
    useSession.setState({ user });
    void session.refreshPlaylists();
  },

  async signOut() {
    await api.logout().catch(() => undefined);
    useSession.setState({ user: null, playlists: [] });
  },

  async refreshPlaylists() {
    try {
      useSession.setState({ playlists: await api.playlists() });
    } catch {
      // Shown as empty; the page itself reports errors.
    }
  },

  setUser(user: User) {
    useSession.setState({ user });
  },
};

setSignedOutHandler(() => useSession.setState({ user: null }));
