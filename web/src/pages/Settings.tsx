import { useState } from "react";
import { Bot, Copy, LogOut, Monitor, Smartphone } from "lucide-react";
import { api } from "../lib/api";
import { when } from "../lib/format";
import { session, useSession } from "../lib/session";
import { QUALITY_STEPS, qualityLabel, usePrefs } from "../player/prefs";
import { SourcesSection } from "../components/Sources";
import { Button, errorToast, Field, IconButton, PageHeader, Spinner, toast, Toggle, useLoad } from "../components/ui";

export function Settings() {
  const user = useSession((s) => s.user)!;
  // A new connector link shows in the list of sign-ins at once.
  const [signIns, setSignIns] = useState(0);
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
        <Devices key={signIns} />
      </section>

      <section className="panel">
        <h2>Claude connector</h2>
        <ClaudeConnector onMade={() => setSignIns((n) => n + 1)} />
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
      <Toggle label="Compare every service for the best copy" help="Starts songs a moment later, but asks every service and picks the best copy that fits your quality setting." checked={prefs.thorough} onChange={(thorough) => usePrefs.setState({ thorough })} />
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
            {s.kind === "APP" ? <Smartphone size={20} /> : s.kind.startsWith("MCP") ? <Bot size={20} /> : <Monitor size={20} />}
            <div className="source-text">
              <div className="source-name">{s.name}{s.current && <span className="badge">This browser</span>}</div>
              <div className="source-sub">{s.kind.startsWith("MCP") ? "Link made" : "Signed in"} {when(s.createdAt)} · last used {when(s.lastSeenAt)}</div>
            </div>
            {!s.current && (
              <IconButton label={s.kind.startsWith("MCP") ? "Remove this connector link" : "Sign out this device"} onClick={async () => {
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

/**
 * A link that lets Claude look at this server (its status, warnings, SiriusXM's stream log, stream
 * tests, devices, search and history; it can't change anything), added in Claude as a custom
 * connector. Shown once; listed and removed with the sign-ins above.
 */
function ClaudeConnector({ onMade }: { onMade: () => void }) {
  const admin = useSession((s) => s.user)?.role === "ADMIN";
  const [link, setLink] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  // An admin's link can see every account (their SiriusXM, devices, history), to troubleshoot anyone's.
  const [everyone, setEveryone] = useState(true);
  return (
    <div className="form-stack">
      <p className="muted">
        Lets Claude look at this server to help troubleshoot it: its status and recent warnings, SiriusXM's stream log, stream tests,
        your devices, search and your listening history. It can only look, never change anything, and sees only what your account sees.
        In Claude, add it under Settings › Connectors › Add custom connector.
      </p>
      {link ? (
        <>
          <div className="copy-row">
            <code>{link}</code>
            <Button variant="ghost" onClick={() => { void navigator.clipboard?.writeText(link); toast("Copied"); }}><Copy size={16} /> Copy</Button>
          </div>
          <p className="muted">This link is the key: keep it private. It's shown only now; it's listed above as "Claude connector", where you can remove it.</p>
        </>
      ) : (
        <>
          {admin && (
            <Toggle
              checked={everyone}
              onChange={setEveryone}
              label="See every account"
              help="As an admin, the link can see every account's sources, SiriusXM, devices and listening history (and the server's log), to troubleshoot anyone's. Off: only yours."
            />
          )}
          <div>
            <Button busy={busy} onClick={async () => {
              setBusy(true);
              try { setLink((await api.mcpLink(admin && everyone)).url); onMade(); } catch (e) { errorToast(e); }
              setBusy(false);
            }}><Bot size={18} /> Make a connector link</Button>
          </div>
        </>
      )}
    </div>
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
