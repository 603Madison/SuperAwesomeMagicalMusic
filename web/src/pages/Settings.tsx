import { useState } from "react";
import { LogOut, Monitor, Smartphone } from "lucide-react";
import { api } from "../lib/api";
import { when } from "../lib/format";
import { session, useSession } from "../lib/session";
import { QUALITY_STEPS, qualityLabel, usePrefs } from "../player/prefs";
import { SourcesSection } from "../components/Sources";
import { Button, errorToast, Field, IconButton, PageHeader, Spinner, toast, Toggle, useLoad } from "../components/ui";

export function Settings() {
  const user = useSession((s) => s.user)!;
  return (
    <div className="page settings">
      <PageHeader title="Settings" />

      <section className="panel">
        <h2>Sources</h2>
        <p className="muted">Where your music comes from. Searches ask all of them; playback prefers your library, then the best copy within your quality setting.</p>
        <SourcesSection scope="personal" />
      </section>

      <section className="panel">
        <h2>Playback in this browser</h2>
        <PlaybackSettings />
      </section>

      <section className="panel">
        <h2>Account</h2>
        <Profile />
        {user.hasPassword && <PasswordChange />}
        {user.oidc && !user.hasPassword && <p className="muted">You sign in through single sign-on; your password is managed there.</p>}
      </section>

      <section className="panel">
        <h2>Signed in on</h2>
        <Devices />
      </section>

      <section className="panel">
        <h2>Lidarr</h2>
        <LidarrRequests />
      </section>
    </div>
  );
}

function PlaybackSettings() {
  const prefs = usePrefs();
  return (
    <div className="form-stack">
      <Field label="Quality" help="Songs above this are converted down (or a smaller copy is picked). Original plays your files untouched.">
        <select value={prefs.kbps} onChange={(e) => usePrefs.setState({ kbps: Number(e.target.value) })}>
          {QUALITY_STEPS.map((k) => <option key={k} value={k}>{qualityLabel(k)}</option>)}
        </select>
      </Field>
      <Toggle label="Even out volume" help="Songs from different services play at about the same loudness." checked={prefs.levelling} onChange={(levelling) => usePrefs.setState({ levelling })} />
      <Toggle label="Compare every service for the best copy" help="Starts songs a moment later, but picks e.g. a Qobuz FLAC over a YouTube stream." checked={prefs.thorough} onChange={(thorough) => usePrefs.setState({ thorough })} />
    </div>
  );
}

function Profile() {
  const user = useSession((s) => s.user)!;
  const [name, setName] = useState(user.displayName);
  const [busy, setBusy] = useState(false);
  return (
    <form className="inline-form" onSubmit={async (e) => {
      e.preventDefault();
      setBusy(true);
      try { session.setUser(await api.updateProfile(name)); toast("Saved"); } catch (err) { errorToast(err); }
      setBusy(false);
    }}>
      <Field label="Name" help={`Username: ${user.username}${user.role === "ADMIN" ? " · Admin" : ""}`}>
        <input value={name} onChange={(e) => setName(e.target.value)} maxLength={64} />
      </Field>
      <Button busy={busy} disabled={name.trim() === user.displayName} type="submit">Save</Button>
    </form>
  );
}

function PasswordChange() {
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [busy, setBusy] = useState(false);
  return (
    <form className="form-stack" onSubmit={async (e) => {
      e.preventDefault();
      setBusy(true);
      try {
        await api.changePassword(current, next);
        setCurrent("");
        setNext("");
        toast("Password changed. Other devices were signed out.");
      } catch (err) { errorToast(err); }
      setBusy(false);
    }}>
      <h3>Change password</h3>
      <div className="two-col">
        <Field label="Current password"><input type="password" autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} required /></Field>
        <Field label="New password" help="At least 8 characters."><input type="password" autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} minLength={8} required /></Field>
      </div>
      <div><Button busy={busy} type="submit">Change password</Button></div>
    </form>
  );
}

function Devices() {
  const { data, error, loading, setData } = useLoad(() => api.sessions(), []);
  if (loading && !data) return <Spinner />;
  if (error) return <div className="notice notice-error">{error}</div>;
  return (
    <>
      <ul className="device-list">
        {(data ?? []).map((s) => (
          <li key={s.id} className="device-row">
            {s.kind === "APP" ? <Smartphone size={20} /> : <Monitor size={20} />}
            <div className="source-text">
              <div className="source-name">{s.name}{s.current && <span className="badge">This browser</span>}</div>
              <div className="source-sub">Signed in {when(s.createdAt)} · last used {when(s.lastSeenAt)}</div>
            </div>
            {!s.current && (
              <IconButton label="Sign out this device" onClick={async () => {
                try { await api.revokeSession(s.id); setData((data ?? []).filter((x) => x.id !== s.id)); } catch (e) { errorToast(e); }
              }}><LogOut size={18} /></IconButton>
            )}
          </li>
        ))}
      </ul>
      <Button variant="ghost" onClick={() => session.signOut()}><LogOut size={18} /> Sign out here</Button>
    </>
  );
}

function LidarrRequests() {
  const { data } = useLoad(() => api.lidarrRequests(), []);
  if (!data) return <Spinner />;
  if (!data.length) return <p className="muted">When you play songs that aren't in your library, their artists are added to Lidarr (if it's set up and adding is on). They'll show here.</p>;
  const labels = { added: "Added", present: "Already there", not_found: "Not found", failed: "Couldn't add" } as const;
  return (
    <ul className="device-list">
      {data.map((r) => (
        <li key={r.artist} className="device-row">
          <div className="source-text">
            <div className="source-name">{r.artist} <span className={`badge status-${r.status}`}>{labels[r.status]}</span></div>
            <div className="source-sub">{r.detail} · {when(r.updatedAt)}</div>
          </div>
        </li>
      ))}
    </ul>
  );
}
