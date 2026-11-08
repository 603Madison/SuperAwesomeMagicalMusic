import { useEffect, useState } from "react";
import { Globe, Pencil, Plus, Trash2, User as UserIcon, Zap } from "lucide-react";
import { api, type Choice } from "../lib/api";
import type { KindSpec, KindsResponse, SourceView } from "../lib/types";
import { Button, Empty, errorToast, Field, IconButton, Modal, Spinner, toast, Toggle, useLoad } from "./ui";

/**
 * Sources: "personal" shows everything the person can use (shared ones can be switched off for
 * themselves); "global" is the admins' list of sources shared with everyone.
 */
export function SourcesSection({ scope }: { scope: "personal" | "global" }) {
  const sources = useLoad(() => api.sources(), []);
  const kinds = useLoad(() => api.sourceKinds(), []);
  const [editing, setEditing] = useState<{ spec: KindSpec; source?: SourceView } | null>(null);
  const [choosing, setChoosing] = useState(false);
  const [deleting, setDeleting] = useState<SourceView | null>(null);

  if (sources.loading && !sources.data) return <Spinner />;
  if (sources.error) return <div className="notice notice-error">{sources.error}</div>;
  const list = (sources.data ?? []).filter((s) => scope === "personal" || s.scope === "global");
  const canAdd = scope === "global" ? kinds.data?.canAddGlobal : kinds.data?.canAddPersonal;
  const specOf = (kind: string) => kinds.data?.kinds.find((k) => k.kind === kind);

  const update = (next: SourceView) => sources.setData((sources.data ?? []).map((s) => (s.id === next.id ? next : s)));

  return (
    <div className="sources">
      {!list.length && <Empty title="No sources yet">{scope === "global" ? "Add the services everyone on this server can use." : "Add a service to listen from."}</Empty>}
      <ul className="source-list">
        {list.map((source) => {
          const shared = source.scope === "global";
          const own = source.editable && (scope === "global" || !shared);
          return (
            <li key={source.id} className={`source-row ${!source.enabled || source.hidden ? "is-off" : ""}`}>
              <span className={`source-icon kind-${source.kind.toLowerCase()}`}>{source.name.slice(0, 1)}</span>
              <div className="source-text">
                <div className="source-name">{source.name}</div>
                <div className="source-sub">
                  {shared ? <><Globe size={12} /> Shared by the admins</> : <><UserIcon size={12} /> Yours</>}
                  {" · "}{specOf(source.kind)?.label ?? source.kind}
                  {typeof source.settings.serverUrl === "string" ? ` · ${source.settings.serverUrl}` : ""}
                  {typeof source.settings.username === "string" && source.settings.username ? ` · ${source.settings.username}` : ""}
                </div>
              </div>
              <div className="source-actions">
                {shared && scope === "personal" && (
                  <Toggle
                    label="Use"
                    checked={!source.hidden}
                    onChange={async (use) => { try { update(await api.hideSource(source.id, !use)); } catch (e) { errorToast(e); } }}
                  />
                )}
                {own && (
                  <Toggle
                    label="On"
                    checked={source.enabled}
                    onChange={async (on) => { try { update(await api.updateSource(source.id, { enabled: on })); } catch (e) { errorToast(e); } }}
                  />
                )}
                {source.kind !== "YOUTUBE_MUSIC" && (
                  <IconButton label="Test" onClick={async () => {
                    try {
                      const result = await api.testSource(source.id);
                      toast(result.message, result.ok ? "info" : "error");
                    } catch (e) { errorToast(e); }
                  }}><Zap size={18} /></IconButton>
                )}
                {own && specOf(source.kind) && (
                  <IconButton label="Edit" onClick={() => setEditing({ spec: specOf(source.kind)!, source })}><Pencil size={18} /></IconButton>
                )}
                {own && <IconButton label="Remove" onClick={() => setDeleting(source)}><Trash2 size={18} /></IconButton>}
              </div>
            </li>
          );
        })}
      </ul>
      {canAdd && <Button onClick={() => setChoosing(true)}><Plus size={18} /> {scope === "global" ? "Add a shared source" : "Add a source"}</Button>}
      {kinds.data && !kinds.data.canAddPersonal && scope === "personal" && <p className="muted">This server's admins add the sources.</p>}

      {choosing && kinds.data && (
        <KindPicker kinds={kinds.data} onClose={() => setChoosing(false)} onPick={(spec) => { setChoosing(false); setEditing({ spec }); }} />
      )}
      {editing && (
        <SourceDialog
          spec={editing.spec}
          source={editing.source}
          scope={scope}
          onClose={() => setEditing(null)}
          onSaved={(saved) => {
            const exists = (sources.data ?? []).some((s) => s.id === saved.id);
            sources.setData(exists ? (sources.data ?? []).map((s) => (s.id === saved.id ? saved : s)) : [...(sources.data ?? []), saved]);
            setEditing(null);
          }}
        />
      )}
      {deleting && (
        <Modal title={`Remove ${deleting.name}?`} onClose={() => setDeleting(null)} footer={<>
          <Button variant="ghost" onClick={() => setDeleting(null)}>Cancel</Button>
          <Button variant="danger" onClick={async () => {
            try {
              await api.deleteSource(deleting.id);
              sources.setData((sources.data ?? []).filter((s) => s.id !== deleting.id));
              setDeleting(null);
            } catch (e) { errorToast(e); }
          }}>Remove</Button>
        </>}>
          <p>{deleting.scope === "global" ? "Nobody on the server will be able to use it any more." : "Its saved sign-in is deleted."} Songs from it in playlists are found in your other sources where possible.</p>
        </Modal>
      )}
    </div>
  );
}

function KindPicker({ kinds, onPick, onClose }: { kinds: KindsResponse; onPick: (k: KindSpec) => void; onClose: () => void }) {
  return (
    <Modal title="Add a source" onClose={onClose}>
      <div className="kind-list">
        {kinds.kinds.map((k) => (
          <button key={k.kind} className="kind-option" onClick={() => onPick(k)}>
            <span className={`source-icon kind-${k.kind.toLowerCase()}`}>{k.label.slice(0, 1)}</span>
            <span>
              <span className="source-name">{k.label}</span>
              <span className="source-sub">{k.description}</span>
            </span>
          </button>
        ))}
      </div>
    </Modal>
  );
}

function SourceDialog({ spec, source, scope, onClose, onSaved }: {
  spec: KindSpec;
  source?: SourceView;
  scope: "personal" | "global";
  onClose: () => void;
  onSaved: (s: SourceView) => void;
}) {
  const [name, setName] = useState(source?.name ?? "");
  const [values, setValues] = useState<Record<string, string | boolean>>(() => {
    const initial: Record<string, string | boolean> = {};
    for (const f of spec.fields) {
      const saved = source?.settings[f.key];
      if (f.secret) initial[f.key] = "";
      else if (f.type === "bool") initial[f.key] = saved != null ? Boolean(saved) : f.default !== "false";
      else initial[f.key] = saved != null ? String(saved) : f.default ?? "";
    }
    for (const key of ["qualityProfileId", "metadataProfileId", "rootFolderPath"]) {
      if (source?.settings[key] != null) initial[key] = String(source.settings[key]);
    }
    return initial;
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [lidarr, setLidarr] = useState<{ qualityProfiles: Choice[]; metadataProfiles: Choice[]; rootFolders: Choice[] } | null>(null);

  useEffect(() => {
    if (source && spec.kind === "LIDARR") api.lidarrOptions(source.id).then(setLidarr, () => undefined);
  }, [source, spec.kind]);

  const save = async () => {
    setBusy(true);
    setError(null);
    // Secrets left empty keep what's saved.
    const fields = Object.fromEntries(Object.entries(values).filter(([, v]) => v !== ""));
    try {
      const saved = source
        ? await api.updateSource(source.id, { name: name.trim() || undefined, fields })
        : await api.addSource(spec.kind, name.trim(), scope, fields);
      toast(source ? `Saved ${saved.name}` : `Added ${saved.name}`);
      onSaved(saved);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setBusy(false);
    }
  };

  const set = (key: string, value: string | boolean) => setValues((v) => ({ ...v, [key]: value }));

  return (
    <Modal
      title={source ? `Edit ${source.name}` : `Add ${spec.label}${scope === "global" ? " for everyone" : ""}`}
      onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} onClick={save}>{source ? "Save" : "Add"}</Button></>}
    >
      <p className="muted">{spec.description}</p>
      {error && <div className="notice notice-error">{error}</div>}
      <form onSubmit={(e) => { e.preventDefault(); void save(); }} className="form-stack">
        <Field label="Name" help="How it's shown in search results.">
          <input value={name} onChange={(e) => setName(e.target.value)} placeholder={spec.label} maxLength={80} />
        </Field>
        {spec.fields.map((f) =>
          f.type === "bool" ? (
            <Toggle key={f.key} label={f.label} help={f.help} checked={Boolean(values[f.key])} onChange={(v) => set(f.key, v)} />
          ) : f.type === "select" ? (
            <Field key={f.key} label={f.label} help={f.help}>
              <select value={String(values[f.key] ?? "")} onChange={(e) => set(f.key, e.target.value)}>
                {f.options?.map((o) => <option key={o} value={o}>{o}</option>)}
              </select>
            </Field>
          ) : (
            <Field
              key={f.key}
              label={`${f.label}${f.required && !(f.secret && source?.secretsSet.length) ? "" : f.secret ? "" : " (optional)"}`}
              help={f.secret && source ? `Leave empty to keep the saved one. ${f.help ?? ""}` : f.help}
            >
              <input
                type={f.type === "password" ? "password" : f.type === "url" ? "url" : "text"}
                autoComplete={f.type === "password" ? "new-password" : "off"}
                value={String(values[f.key] ?? "")}
                onChange={(e) => set(f.key, e.target.value)}
                required={f.required && !(f.secret && source)}
              />
            </Field>
          ),
        )}
        {lidarr && (
          <>
            <Field label="Quality profile">
              <select value={String(values.qualityProfileId ?? "")} onChange={(e) => set("qualityProfileId", e.target.value)}>
                <option value="">The root folder's default</option>
                {lidarr.qualityProfiles.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
              </select>
            </Field>
            <Field label="Metadata profile">
              <select value={String(values.metadataProfileId ?? "")} onChange={(e) => set("metadataProfileId", e.target.value)}>
                <option value="">The root folder's default</option>
                {lidarr.metadataProfiles.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
              </select>
            </Field>
            <Field label="Root folder">
              <select value={String(values.rootFolderPath ?? "")} onChange={(e) => set("rootFolderPath", e.target.value)}>
                <option value="">The first one</option>
                {lidarr.rootFolders.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
              </select>
            </Field>
          </>
        )}
        <button type="submit" hidden />
      </form>
    </Modal>
  );
}
