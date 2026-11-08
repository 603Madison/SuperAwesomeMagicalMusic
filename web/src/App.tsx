import { useEffect, useState } from "react";
import { BrowserRouter, Navigate, NavLink, Route, Routes, useLocation } from "react-router-dom";
import { History as HistoryIcon, ListMusic, Radio as RadioIcon, Search as SearchIcon, Settings as SettingsIcon, Shield, Disc3 } from "lucide-react";
import { session, useSession } from "./lib/session";
import { usePlayer } from "./player/player";
import { PlayerBar, useKeyboardShortcuts } from "./components/PlayerBar";
import { Queue } from "./components/Queue";
import { Spinner, Toasts } from "./components/ui";
import { Mosaic } from "./components/Artwork";
import { Login } from "./pages/Login";
import { ConnectApp } from "./pages/ConnectApp";
import { Search } from "./pages/Search";
import { AlbumView, ArtistView } from "./pages/Browse";
import { PlaylistDetail, Playlists } from "./pages/Playlists";
import { History } from "./pages/History";
import { Radio } from "./pages/Radio";
import { NowPlaying } from "./pages/NowPlaying";
import { Settings } from "./pages/Settings";
import { Admin } from "./pages/Admin";

export function App() {
  useEffect(() => { void session.start(); }, []);
  return (
    <BrowserRouter>
      <Root />
      <Toasts />
    </BrowserRouter>
  );
}

function Root() {
  const loading = useSession((s) => s.loading);
  const user = useSession((s) => s.user);
  const location = useLocation();
  const here = `${location.pathname}${location.search}`;

  if (loading) return <div className="splash"><Spinner size={32} /></div>;
  if (location.pathname === "/login") return user ? <Navigate to={new URLSearchParams(location.search).get("return") ?? "/"} replace /> : <Login />;
  if (!user) return <Navigate to={`/login?return=${encodeURIComponent(here)}`} replace />;
  if (location.pathname === "/connect-app") return <ConnectApp />;
  return <Shell />;
}

function Shell() {
  const user = useSession((s) => s.user)!;
  const playlists = useSession((s) => s.playlists);
  const [queueOpen, setQueueOpen] = useState(false);
  const hasTrack = usePlayer((s) => s.index >= 0);
  const location = useLocation();
  useKeyboardShortcuts();
  useEffect(() => { if (location.pathname === "/now-playing") setQueueOpen(false); }, [location.pathname]);

  return (
    <div className={`shell ${queueOpen ? "with-queue" : ""} ${hasTrack ? "" : "no-track"}`}>
      <nav className="sidebar" aria-label="Main">
        <div className="brand">
          <img src="/icon.png" alt="" width={32} height={32} />
          <span>SuperAwesome<wbr />MagicalMusic</span>
        </div>
        <NavLink to="/" end className="nav-link"><SearchIcon size={20} /> <span>Search</span></NavLink>
        <NavLink to="/now-playing" className="nav-link nav-now"><Disc3 size={20} /> <span>Now playing</span></NavLink>
        <NavLink to="/radio" className="nav-link"><RadioIcon size={20} /> <span>Radio</span></NavLink>
        <NavLink to="/playlists" end className="nav-link"><ListMusic size={20} /> <span>Playlists</span></NavLink>
        <NavLink to="/history" className="nav-link"><HistoryIcon size={20} /> <span>History</span></NavLink>
        <NavLink to="/settings" className="nav-link"><SettingsIcon size={20} /> <span>Settings</span></NavLink>
        {user.role === "ADMIN" && <NavLink to="/admin" className="nav-link"><Shield size={20} /> <span>Admin</span></NavLink>}
        {playlists.length > 0 && (
          <div className="sidebar-playlists">
            <h3>Your playlists</h3>
            {playlists.map((p) => (
              <NavLink key={p.id} to={`/playlists/${p.id}`} className="nav-playlist">
                <Mosaic urls={p.artwork} size={32} />
                <span>{p.name}</span>
              </NavLink>
            ))}
          </div>
        )}
        <div className="sidebar-user">{user.displayName}</div>
      </nav>

      <main className="content">
        <Routes>
          <Route path="/" element={<Search />} />
          <Route path="/artist" element={<ArtistView />} />
          <Route path="/album" element={<AlbumView />} />
          <Route path="/now-playing" element={<NowPlaying />} />
          <Route path="/radio" element={<Radio />} />
          <Route path="/playlists" element={<Playlists />} />
          <Route path="/playlists/:id" element={<PlaylistDetail />} />
          <Route path="/history" element={<History />} />
          <Route path="/settings" element={<Settings />} />
          {user.role === "ADMIN" && <Route path="/admin" element={<Admin />} />}
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>

      {queueOpen && <aside className="queue-panel" aria-label="Queue"><Queue onClose={() => setQueueOpen(false)} /></aside>}
      <PlayerBar queueOpen={queueOpen} onToggleQueue={() => setQueueOpen((o) => !o)} />
    </div>
  );
}
