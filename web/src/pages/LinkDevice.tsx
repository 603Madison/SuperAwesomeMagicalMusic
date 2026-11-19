import { useEffect, useRef, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { MonitorSmartphone } from "lucide-react";
import { api } from "../lib/api";
import type { DeviceRequest } from "../lib/types";
import { useSession } from "../lib/session";
import { Button } from "../components/ui";

/** "bcdf ghjk" as "BCDF-GHJK": capitals, the dash put in, nothing else. */
function tidy(typed: string): string {
  const raw = typed.toUpperCase().replace(/[^A-Z0-9]/g, "").slice(0, 8);
  return raw.length > 4 ? `${raw.slice(0, 4)}-${raw.slice(4)}` : raw;
}

/**
 * Signing in another device with a code: a flip phone (or anything awkward to type a password
 * on) shows a code; typed here, it's signed in to this account once the person allows it.
 */
export function LinkDevice() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const user = useSession((s) => s.user);
  const config = useSession((s) => s.config);
  const [code, setCode] = useState(() => tidy(params.get("code") ?? ""));
  const [request, setRequest] = useState<DeviceRequest | null>(null);
  const [done, setDone] = useState<"allowed" | "denied" | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const looked = useRef(false);

  const look = async (typed: string) => {
    setBusy(true);
    setError(null);
    try {
      setRequest(await api.deviceRequest(typed));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    setBusy(false);
  };

  // A code from the link (the device's QR code) is looked up at once; it still needs allowing.
  useEffect(() => {
    if (looked.current) return;
    looked.current = true;
    if (code.length === 9) void look(code);
  }, [code]);

  const decide = async (allow: boolean) => {
    if (!request) return;
    setBusy(true);
    setError(null);
    try {
      await api.deviceDecide(request.userCode, allow);
      setDone(allow ? "allowed" : "denied");
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    setBusy(false);
  };

  const startOver = () => {
    setRequest(null);
    setDone(null);
    setError(null);
    setCode("");
  };

  return (
    <div className="login-page">
      <div className="login-card">
        <div className="connect-icon"><MonitorSmartphone size={36} /></div>
        {done ? (
          <>
            <h1>{done === "allowed" ? "It's signed in" : "Turned down"}</h1>
            <p className="login-sub">
              {done === "allowed"
                ? <><strong>{request?.deviceName}</strong> is signed in as {user?.displayName}. It's listed with your devices in Settings, where you can sign it out.</>
                : <><strong>{request?.deviceName}</strong> won't be signed in.</>}
            </p>
            <Button variant="primary" className="wide" onClick={() => navigate("/", { replace: true })}>Go to the player</Button>
            <Button variant="ghost" className="wide" onClick={startOver}>Sign in another device</Button>
          </>
        ) : request ? (
          <>
            <h1>Sign in this device?</h1>
            <p className="login-sub">
              <strong>{request.deviceName}</strong> wants to sign in to {config?.serverName ?? "this server"} as{" "}
              <strong>{user?.displayName}</strong> ({user?.username}). It will see your playlists and sources.
            </p>
            <div className="link-code" aria-label="The code">{request.userCode}</div>
            {error && <div className="notice notice-error" role="alert">{error}</div>}
            <Button variant="primary" className="wide" busy={busy} onClick={() => void decide(true)}>Allow</Button>
            <Button variant="ghost" className="wide" disabled={busy} onClick={() => void decide(false)}>Deny</Button>
            <p className="login-version">Only allow it if the device is in front of you and you started signing in on it yourself.</p>
          </>
        ) : (
          <>
            <h1>Sign in a device</h1>
            <p className="login-sub">Type the code the device shows.</p>
            {error && <div className="notice notice-error" role="alert">{error}</div>}
            <form className="login-form" onSubmit={(e) => { e.preventDefault(); void look(code); }}>
              <input
                className="link-code-input"
                aria-label="Code"
                placeholder="XXXX-XXXX"
                autoFocus
                autoComplete="off"
                autoCapitalize="characters"
                spellCheck={false}
                value={code}
                onChange={(e) => setCode(tidy(e.target.value))}
              />
              <Button variant="primary" className="wide" busy={busy} type="submit" disabled={code.length !== 9}>Continue</Button>
            </form>
            <p className="login-version">Signed in as {user?.displayName} ({user?.username}).</p>
          </>
        )}
      </div>
    </div>
  );
}
