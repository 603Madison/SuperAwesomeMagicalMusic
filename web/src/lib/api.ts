import type {
  ArtistView, ChannelView, NowView, StationTracks,
  AdminSettings, AuthConfig, KindsResponse, LidarrRequest, Listen, LyricsView, Playlist, PlaylistSummary,
  ResolvedStream, SearchResults, ArtistPage, AlbumPage, ServerSettings, SessionInfo, SourceView, Track, User, OidcSettings,
} from "./types";

/** An error from the server, with a message meant for people. */
export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

/** Told when the server says the sign-in is gone, so the app can show the sign-in page. */
let onSignedOut: (() => void) | null = null;
export function setSignedOutHandler(handler: () => void) {
  onSignedOut = handler;
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = {};
  // Every change carries this header, which other sites' pages can't send.
  if (method !== "GET") headers["X-SAMM"] = "1";
  if (body !== undefined) headers["Content-Type"] = "application/json";
  let response: Response;
  try {
    response = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), credentials: "same-origin" });
  } catch {
    throw new ApiError(0, "Can't reach the server");
  }
  const text = await response.text();
  const data = text ? safeJson(text) : null;
  if (!response.ok) {
    if (response.status === 401 && !path.startsWith("/api/auth/")) onSignedOut?.();
    const message = (data && typeof data === "object" && "error" in data && typeof data.error === "string") ? data.error : `The server answered ${response.status}`;
    throw new ApiError(response.status, message);
  }
  return data as T;
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

const get = <T>(path: string) => request<T>("GET", path);
const post = <T>(path: string, body?: unknown) => request<T>("POST", path, body ?? {});
const patch = <T>(path: string, body: unknown) => request<T>("PATCH", path, body);
const put = <T>(path: string, body: unknown) => request<T>("PUT", path, body);
const del = <T>(path: string) => request<T>("DELETE", path);
const q = encodeURIComponent;

export const api = {
  authConfig: () => get<AuthConfig>("/api/auth/config"),
  login: (username: string, password: string) => post<{ user: User }>("/api/auth/login", { username, password }),
  logout: () => post<unknown>("/api/auth/logout"),
  appCode: (challenge: string) => post<{ code: string }>("/api/auth/app/code", { challenge }),

  me: () => get<{ user: User; session: SessionInfo }>("/api/me"),
  updateProfile: (displayName: string) => patch<User>("/api/me", { displayName }),
  changePassword: (current: string, next: string) => post<unknown>("/api/me/password", { current, new: next }),
  sessions: () => get<SessionInfo[]>("/api/me/sessions"),
  revokeSession: (id: string) => del<unknown>(`/api/me/sessions/${q(id)}`),

  search: (query: string) => get<SearchResults>(`/api/search?q=${q(query)}`),
  artist: (id: string) => get<ArtistPage>(`/api/artist?id=${q(id)}`),
  artistNamed: (name: string) => get<ArtistPage>(`/api/artist?name=${q(name)}`),
  album: (id: string) => get<AlbumPage>(`/api/album?id=${q(id)}`),
  albumNamed: (title: string, artist: string) => get<AlbumPage>(`/api/album?title=${q(title)}&artist=${q(artist)}`),
  resolve: (track: Track, kbps: number, codecs: string[], thorough: boolean) =>
    post<ResolvedStream>("/api/play/resolve", { track, kbps, codecs, thorough }),
  lyrics: (track: Track) => post<LyricsView>("/api/lyrics", { track }),
  lidarrRequests: () => get<LidarrRequest[]>("/api/lidarr/requests"),

  playlists: () => get<PlaylistSummary[]>("/api/playlists"),
  playlist: (id: string) => get<Playlist>(`/api/playlists/${q(id)}`),
  createPlaylist: (name: string, tracks: Track[] = []) => post<Playlist>("/api/playlists", { name, tracks }),
  importPlaylist: (from: { url?: string; text?: string; name?: string }) =>
    post<{ playlist: Playlist; found: number; missing: string[]; note?: string }>("/api/playlists/import", from),
  renamePlaylist: (id: string, name: string, description?: string) => patch<PlaylistSummary>(`/api/playlists/${q(id)}`, { name, description }),
  deletePlaylist: (id: string) => del<unknown>(`/api/playlists/${q(id)}`),
  addToPlaylist: (id: string, tracks: Track[], position?: number) => post<Playlist>(`/api/playlists/${q(id)}/tracks`, { tracks, position }),
  removeFromPlaylist: (id: string, entryIds: string[]) => post<Playlist>(`/api/playlists/${q(id)}/remove`, { entryIds }),
  reorderPlaylist: (id: string, revision: number, entryIds: string[]) => put<Playlist>(`/api/playlists/${q(id)}/order`, { revision, entryIds }),

  history: (before?: number) => get<Listen[]>(`/api/history?limit=100${before ? `&before=${before}` : ""}`),
  addListens: (listens: Listen[]) => post<unknown>("/api/history", { listens }),
  clearHistory: () => del<unknown>("/api/history"),

  sourceKinds: () => get<KindsResponse>("/api/sources/kinds"),
  sources: () => get<SourceView[]>("/api/sources"),
  addSource: (kind: string, name: string, scope: "global" | "personal", fields: Record<string, unknown>) =>
    post<SourceView>("/api/sources", { kind, name, scope, fields }),
  updateSource: (id: string, change: { name?: string; enabled?: boolean; fields?: Record<string, unknown> }) =>
    patch<SourceView>(`/api/sources/${q(id)}`, change),
  deleteSource: (id: string) => del<unknown>(`/api/sources/${q(id)}`),
  hideSource: (id: string, hidden: boolean) => put<SourceView>(`/api/sources/${q(id)}/hidden`, { hidden }),
  testSource: (id: string) => post<{ ok: boolean; message: string }>(`/api/sources/${q(id)}/test`),
  lidarrOptions: (id: string) =>
    get<{ qualityProfiles: Choice[]; metadataProfiles: Choice[]; rootFolders: Choice[] }>(`/api/sources/${q(id)}/lidarr-options`),

  channels: (src: string) => get<ChannelView[]>(`/api/live/sxm/${q(src)}/channels`),
  channelNow: async (src: string, type: string, id: string, at: number): Promise<NowView | null> => {
    const result = await get<NowView | null>(`/api/live/sxm/${q(src)}/now?type=${q(type)}&id=${q(id)}&at=${Math.round(at)}`);
    return result && typeof result === "object" ? result : null;
  },
  sxmArtists: (src: string, query: string) => get<ArtistView[]>(`/api/live/sxm/${q(src)}/artists?q=${q(query)}`),
  station: (src: string, body: { artist?: ArtistView; stationId?: string; name?: string; more?: boolean }) =>
    post<StationTracks>(`/api/live/sxm/${q(src)}/station`, body),
  stationProgress: (src: string, stationId: string, trackId: string, elapsedMs: number, start: boolean) =>
    post<unknown>(`/api/live/sxm/${q(src)}/station-track/${q(stationId)}/${q(trackId)}/progress`, { elapsedMs, start }),
  stationThumb: (src: string, stationId: string, trackId: string, thumb: "UP" | "DOWN" | "NONE", elapsedMs: number) =>
    post<unknown>(`/api/live/sxm/${q(src)}/station-track/${q(stationId)}/${q(trackId)}/thumb`, { thumb, elapsedMs }),
  radioSearch: (src: string, query: string, country?: string) =>
    get<Track[]>(`/api/live/radio/${q(src)}/search?q=${q(query)}${country ? `&country=${q(country)}` : ""}`),
  radioNearby: (src: string, lat: number, lon: number) => get<Track[]>(`/api/live/radio/${q(src)}/nearby?lat=${lat}&lon=${lon}`),
  radioNow: (src: string, id: string) => get<{ title?: string }>(`/api/live/radio/${q(src)}/station/${q(id)}/now`),

  users: () => get<User[]>("/api/admin/users"),
  createUser: (username: string, displayName: string, password: string, role: string) =>
    post<User>("/api/admin/users", { username, displayName, password, role }),
  updateUser: (id: string, change: { displayName?: string; role?: string; disabled?: boolean }) => patch<User>(`/api/admin/users/${q(id)}`, change),
  deleteUser: (id: string) => del<unknown>(`/api/admin/users/${q(id)}`),
  resetPassword: (id: string, password: string) => post<unknown>(`/api/admin/users/${q(id)}/password`, { password }),
  signOutUser: (id: string) => del<unknown>(`/api/admin/users/${q(id)}/sessions`),
  adminSettings: () => get<AdminSettings>("/api/admin/settings"),
  saveSettings: (settings: ServerSettings, oidcClientSecret?: string) => put<AdminSettings>("/api/admin/settings", { settings, oidcClientSecret }),
  testOidc: (oidc: OidcSettings) => post<{ ok: boolean; message: string }>("/api/admin/oidc/test", oidc),
};

export interface Choice {
  id: string;
  name: string;
}

/** A sized copy of server artwork (the server picks the service's nearest size). */
export function art(url: string | undefined, px: number): string | undefined {
  if (!url) return undefined;
  return url.startsWith("/api/art?") ? `${url}&px=${px}` : url;
}
