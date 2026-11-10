// The server's API, as the web player sees it (see server/src/main/kotlin).

export type SourceType = "JELLYFIN" | "QOBUZ" | "YOUTUBE_MUSIC" | "SIRIUSXM" | "RADIO";
export type SourceKind = "JELLYFIN" | "QOBUZ" | "YOUTUBE_MUSIC" | "LIDARR" | "SIRIUSXM" | "RADIO";
export type Role = "ADMIN" | "USER";

export interface Track {
  source: SourceType;
  sourceId: string;
  title: string;
  artist: string;
  album?: string;
  durationMs?: number;
  artworkUrl?: string;
  isrc?: string;
  /** The saved source the song came from. */
  via?: string;
}

export interface User {
  id: string;
  username: string;
  displayName: string;
  role: Role;
  breakGlass: boolean;
  disabled: boolean;
  oidc: boolean;
  hasPassword: boolean;
  createdAt: number;
  lastLoginAt?: number;
}

export interface SessionInfo {
  id: string;
  /** A browser, the app, or a Claude connector link (an admin's may see every account). */
  kind: "WEB" | "APP" | "MCP" | "MCP_ALL";
  name: string;
  createdAt: number;
  lastSeenAt: number;
  expiresAt: number;
  current: boolean;
}

export interface AuthConfig {
  serverName: string;
  version: string;
  localLogin: boolean;
  oidc?: { label: string };
}

export interface FieldSpec {
  key: string;
  label: string;
  type: "text" | "url" | "password" | "bool" | "select";
  required: boolean;
  secret: boolean;
  help?: string;
  options?: string[];
  default?: string;
}

export interface KindSpec {
  kind: SourceKind;
  label: string;
  description: string;
  fields: FieldSpec[];
}

export interface KindsResponse {
  kinds: KindSpec[];
  canAddPersonal: boolean;
  canAddGlobal: boolean;
}

export interface SourceView {
  id: string;
  kind: SourceKind;
  name: string;
  scope: "global" | "personal";
  enabled: boolean;
  hidden: boolean;
  editable: boolean;
  settings: Record<string, string | number | boolean>;
  secretsSet: string[];
}

export interface SearchGroup {
  sourceId: string;
  sourceName: string;
  kind: SourceKind;
  tracks: Track[];
  error?: string;
}

/** An artist, wherever they were found; [id] opens their page. */
export interface ArtistSummary {
  id: string;
  name: string;
  artworkUrl?: string;
}

/** An album, wherever it was found; [id] opens it, [artistId] its artist. */
export interface AlbumSummary {
  id: string;
  title: string;
  artist: string;
  artistId: string;
  year?: number;
  artworkUrl?: string;
  trackCount?: number;
}

export interface TopResult {
  kind: "artist" | "album" | "song";
  artist?: ArtistSummary;
  album?: AlbumSummary;
  track?: Track;
}

export interface SearchResults {
  query: string;
  top?: TopResult;
  songs: Track[];
  artists: ArtistSummary[];
  albums: AlbumSummary[];
}

export interface ArtistPage {
  artist: ArtistSummary;
  topSongs: Track[];
  albums: AlbumSummary[];
  singles: AlbumSummary[];
}

export interface AlbumPage {
  album: AlbumSummary;
  tracks: Track[];
}

export interface ResolvedStream {
  track: Track;
  streamUrl: string;
  /** Live radio (no end, no seeking); set by the player, not the server. */
  live?: boolean;
  /** An HLS playlist rather than a plain file. */
  hls?: boolean;
  description: string;
  kbps?: number;
  withinCap: boolean;
  gainDb?: number;
  seekable: boolean;
  transcoded: boolean;
  extension: string;
}

export interface PlaylistSummary {
  id: string;
  name: string;
  description: string;
  count: number;
  durationMs: number;
  createdAt: number;
  updatedAt: number;
  revision: number;
  artwork: string[];
  /** When it was last played, on any of the person's devices. */
  playedAt?: number | null;
  /** How many of its songs weren't found (greyed out; looked for again every few hours). */
  missing?: number;
}

export interface PlaylistEntry {
  entryId: string;
  track: Track;
  addedAt: number;
}

export interface Playlist {
  summary: PlaylistSummary;
  entries: PlaylistEntry[];
}

export interface Listen {
  track: Track;
  playedAt: number;
  listenedMs: number;
  client: string;
}

export interface LyricsView {
  lines: { timeMs?: number; text: string }[];
  source: string;
  synced: boolean;
}

export interface LidarrRequest {
  artist: string;
  status: "added" | "present" | "not_found" | "failed";
  detail: string;
  updatedAt: number;
}

export interface OidcSettings {
  enabled: boolean;
  issuer: string;
  clientId: string;
  scopes: string;
  buttonLabel: string;
  usernameClaim: string;
  groupsClaim: string;
  userGroup: string;
  adminGroup: string;
  autoCreateUsers: boolean;
}

export interface ServerSettings {
  serverName: string;
  publicUrl: string;
  localLogin: boolean;
  allowPersonalSources: boolean;
  oidc: OidcSettings;
}

export interface AdminSettings {
  settings: ServerSettings;
  oidcClientSecretSet: boolean;
  redirectUri: string;
  fromEnvironment: string[];
  version: string;
}

export interface ChannelView {
  track: Track;
  number?: number;
  category: string;
  locked: boolean;
}

export interface NowView {
  title: string;
  artist: string;
  album?: string;
  artworkUrl?: string;
  startMs: number;
  durationMs: number;
  song?: Track;
  artistId?: string;
}

export interface ArtistView {
  id: string;
  name: string;
  artworkUrl?: string;
  stationId?: string;
  entityType: string;
}

export interface StationTracks {
  station: Track;
  tracks: Track[];
}
