import { useCallback, useEffect, useRef, useState } from "react";
import { ArrowClockwise, Check } from "@phosphor-icons/react";
import { dashboardRequest, jsonBody } from "../lib/hostApi";
import SetupWizard from "./SetupWizard";
import VersionSelect, { useVersionCatalog, versionIsValid } from "./VersionSelect";

interface SetupCandidate {
  path: string;
  type: string;
  mcVersion: string;
  port: number;
  configured: boolean;
  hasServerFiles: boolean;
  contents: string[];
}

interface HostMinimizeState {
  status: string;
  restartRequired: boolean;
  worldName: string;
  firstSetupStatus: string;
  firstSetupWorld: string;
  firstSetupDisabledPluginCount: string;
  firstSetupConfirmation: string;
  confirmation: string;
  rollbackConfirmation: string;
  notes: string[];
  message?: string;
  heap?: { name: string; applied: boolean; detail: string }[];
}

interface SetupState {
  required: boolean;
  completed: boolean;
  candidates: SetupCandidate[];
  ntfy: { enabled: boolean; server: string; topic: string };
}

export default function SetupWorkflow() {
  const [state, setState] = useState<SetupState | null>(null);
  const [directory] = useState("./child-server");
  const [type, setType] = useState("fabric");
  const [mcVersion, setMcVersion] = useState("");
  const [port, setPort] = useState("2063");
  const [ntfy, setNtfy] = useState({ enabled: false, server: "https://ntfy.sh", topic: "" });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [host, setHost] = useState<HostMinimizeState | null>(null);
  const ntfyInitialized = useRef(false);
  const catalog = useVersionCatalog(type, !!state && !state.required && !state.completed);

  const refresh = useCallback(async () => {
    setError("");
    try {
      const result = await dashboardRequest<SetupState>("/api/setup");
      setState(result);
      setHost(await dashboardRequest<HostMinimizeState>("/api/host-minimize"));
      if (!ntfyInitialized.current) {
        ntfyInitialized.current = true;
        setNtfy(result.ntfy);
      }
      return true;
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Einrichtungsstatus konnte nicht geladen werden.");
      return false;
    }
  }, []);

  useEffect(() => {
    void refresh();
    const onPageChange = (event: Event) => {
      if ((event as CustomEvent<string>).detail === "setup") void refresh();
    };
    window.addEventListener("dashboard:tab", onPageChange);
    return () => window.removeEventListener("dashboard:tab", onPageChange);
  }, [refresh]);

  const submit = async () => {
    setBusy(true);
    setError("");
    try {
      const path = directory.trim();
      if (!versionIsValid(type, mcVersion, catalog.versions, false))
        throw new Error("Bitte eine verfügbare Minecraft-Version auswählen.");
      const accepted = await window.dashboardUi?.confirm({
        title: "Server einrichten?",
        description: `Servertyp ${type} ${mcVersion} wird für ${path} konfiguriert. Es wird noch kein Server-JAR heruntergeladen.`,
        confirmLabel: "Einrichtung bestätigen",
      });
      if (!accepted) return;
      const result = await dashboardRequest<{ warning?: string }>("/api/setup", {
        method: "POST",
        body: jsonBody({ action: "new", path, type, mcVersion, port: Number(port), confirm: "", ntfy }),
      });
      await refresh();
      window.dashboardUi?.notify(
        result.warning ?? "Einrichtung gespeichert. Ein Child-Start ist jetzt möglich.",
        !!result.warning,
      );
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Einrichtung fehlgeschlagen.");
    } finally {
      setBusy(false);
    }
  };

  const hostAction = async (action: "apply" | "rollback", required: string) => {
    const accepted = await window.dashboardUi?.confirm({
      title: action === "apply" ? "Äußeren Spigot verkleinern?" : "Host-Dateien zurückholen?",
      description: action === "apply"
        ? "Ein laufender Child wird zuerst sauber gestoppt. Bestehende Welten, Plugins, Logs und Backups werden nicht gelöscht. Danach musst du den äußeren Spigot selbst im Panel neu starten."
        : "Die gesicherten server.properties, bukkit.yml und spigot.yml werden zurückgeschrieben. Welten werden nicht gelöscht.",
      confirmLabel: action === "apply" ? "Minimierung vorbereiten" : "Zurückholen",
      destructive: true,
    });
    if (!accepted) return;
    setBusy(true);
    try {
      const result = await dashboardRequest<HostMinimizeState>("/api/host-minimize", {
        method: "POST",
        body: jsonBody({ action, confirm: required }),
      });
      setHost(result);
      window.dashboardUi?.notify(result.message ?? "Gespeichert.", false);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Host-Aktion fehlgeschlagen.");
    } finally {
      setBusy(false);
    }
  };

  const runHostFirstSetup = async (required: string) => {
    const accepted = await window.dashboardUi?.confirm({
      title: "Aggressive Host-Grundeinrichtung ausführen?",
      description: "Dies ist für ein Upgrade ausdrücklich manuell. Alle erkannten äußeren Minecraft-Welten werden unwiderruflich entfernt, eine neue Void-Lobby wird eingerichtet und andere Plugin-JARs werden mit .disabled umbenannt. Child-Daten, ServerHoster-Konfiguration, Passwort und Backups bleiben erhalten. Der Host fährt herunter und muss nach der Bestätigung und nach der Vorbereitung jeweils im Hosting-Panel gestartet werden. Die Weltlöschung erfolgt erst nach dem Laden der neuen Lobby und kann nicht zurückgenommen werden.",
      confirmLabel: "Host-Grundeinrichtung starten",
      destructive: true,
    });
    if (!accepted) return;
    setBusy(true);
    setError("");
    try {
      await dashboardRequest("/api/host-first-setup", {
        method: "POST",
        body: jsonBody({ confirm: required }),
      });
      setHost((current) => current ? { ...current, firstSetupStatus: "requested" } : current);
      window.dashboardUi?.notify("Host-Grundeinrichtung bestätigt. Der Host fährt herunter; starte ihn im Hosting-Panel neu.");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Host-Grundeinrichtung konnte nicht gestartet werden.");
    } finally {
      setBusy(false);
    }
  };

  if (!state && !error) return <section className="setup-workflow"><p>Einrichtung wird geladen …</p></section>;
  if (!state) return <section className="setup-workflow"><p className="workflow-error" role="alert">{error}</p><button className="btn" type="button" onClick={() => void refresh()}>Erneut versuchen</button></section>;
  if (state.required && !host && !error) return <section className="setup-workflow"><p>Host-Einstellungen werden geladen …</p></section>;
  if (state.required && !host) return <section className="setup-workflow"><p className="workflow-error" role="alert">{error}</p><button className="btn" type="button" onClick={() => void refresh()}>Erneut versuchen</button></section>;
  // Prefer the wizard while setup is still required. required/completed come from one
  // backend snapshot, but keep the !completed form below for stale client state and
  // transitions while another session finishes configureSetup.
  if (state.required) return <SetupWizard state={state} host={host} onComplete={refresh} />;

  return (
    <section className="setup-workflow">
      <header className="setup-heading">
        <div>
          <p className="eyebrow">EINMALIGE EINRICHTUNG</p>
          <h2>{state?.completed ? "Child-Server eingerichtet" : "Serverhoster einrichten"}</h2>
          <p>Es wird genau ein Child-Server verwaltet. Die Einrichtung lädt keine Mods oder Plugins vorab herunter.</p>
        </div>
        <button className="btn" onClick={() => void refresh()} disabled={busy} aria-label="Erneut laden">
          <ArrowClockwise size={18} />
        </button>
      </header>

      {error && <p className="workflow-error" role="alert">{error}</p>}

      {state?.completed && (
        <>
          <article className="setup-card">
            <Check size={20} />
            <div><b>Einrichtung abgeschlossen</b><p>Serverdateien werden nur bei einem expliziten Start eingerichtet oder aktualisiert.</p></div>
          </article>
        </>
      )}

      {!state?.completed && (
        <>
          <div className="setup-card-list">
            <h3>Server einrichten</h3>
            <label>Serverordner<input value={directory} readOnly /></label>
            <div className="setup-form-grid">
              <label>Servertyp<select value={type} onChange={(event) => { setType(event.target.value); setMcVersion(""); }}>
                {["fabric", "paper", "custom"].map((value) => <option key={value}>{value}</option>)}
              </select></label>
              <VersionSelect type={type} value={mcVersion} onChange={setMcVersion} preserve={false} catalog={catalog} />
              <label>Spielport<input type="number" min="1" max="65535" value={port} onChange={(event) => setPort(event.target.value)} /></label>
            </div>
            <button className="btn pri" disabled={busy || !directory || !versionIsValid(type, mcVersion, catalog.versions, false) || !Number(port)} onClick={() => void submit()}>Konfiguration speichern</button>
          </div>
          <div className="setup-card-list">
            <h3>ntfy-Benachrichtigungen (optional)</h3>
            <label className="setup-checkbox">
              <input type="checkbox" checked={ntfy.enabled} onChange={(event) => setNtfy({ ...ntfy, enabled: event.target.checked })} />
              Benachrichtigungen aktivieren
            </label>
            <div className="setup-form-grid">
              <label>ntfy-Server-URL<input value={ntfy.server} onChange={(event) => setNtfy({ ...ntfy, server: event.target.value })} /></label>
              <label>Thema<input value={ntfy.topic} onChange={(event) => setNtfy({ ...ntfy, topic: event.target.value })} /></label>
            </div>
            <p className="muted">Zugangstoken bleiben außerhalb der config.yml in der lokalen secrets.yml gespeichert.</p>
          </div>
        </>
      )}

      {host && host.firstSetupStatus !== "done" && host.firstSetupStatus !== "" && <div className="setup-card-list">
        <h3>Einmalige Host-Grundeinrichtung</h3>
        <p>Bei einer Neuinstallation automatisch beim ersten Start. Bei einem Upgrade wird aus Sicherheitsgründen nichts automatisch verändert; du kannst die aggressive Grundeinrichtung hier ausdrücklich bestätigen. Sie entfernt alle erkannten äußeren Minecraft-Welten und richtet eine Void-Lobby ein. Andere Plugin-JARs werden in-place umbenannt (Endung .disabled), damit sie nicht geladen werden; ihre Dateien und Konfigurationen bleiben erhalten. Child-Server, ServerHoster-Konfiguration, Dashboard-Passwort und Backups bleiben erhalten. Nach der Vorbereitung fährt der Host herunter und muss im Hosting-Panel gestartet werden. Erst nach dem Laden der neuen Lobby werden alte Welten unwiderruflich gelöscht.</p>
        <p>Status: {host?.firstSetupStatus ?? "unbekannt"}{host?.firstSetupWorld ? ` · Void-Welt ${host.firstSetupWorld}` : ""}{Number(host?.firstSetupDisabledPluginCount ?? 0) > 0 ? ` · deaktivierte Zusatz-Plugins: ${host?.firstSetupDisabledPluginCount}` : ""}</p>
        {host?.firstSetupStatus === "manual-required" && (
          <div className="setup-actions">
            <button className="btn" disabled={busy} type="button" onClick={() => void runHostFirstSetup(host.firstSetupConfirmation || "HOST GRUNDSETUP")}>Host-Grundeinrichtung starten …</button>
          </div>
        )}
        {host?.firstSetupStatus === "restart-required" && <p>Die Einrichtung ist vorbereitet. Bitte den äußeren Host im Hosting-Panel neu starten.</p>}
      </div>}

      <div className="setup-card-list">
        <h3>Host minimieren</h3>
        <p>Wendet die laufenden Host-Optimierungen an, ohne Welten auszuwählen, neu anzulegen oder zu löschen. Sicht- und Simulationsdistanz, Spawns, Nether/End und Host-Whitelist werden minimiert. Child-Server, Welten, Plugin-Dateien, Logs und Backups bleiben erhalten. Port, Online-Mode und ein ausgeschaltetes RCON bleiben unverändert; -Xmx wird nicht gesenkt. Die Whitelist des Childs bleibt unverändert.</p>
        <p>Status: {host?.status ?? "unbekannt"}{host?.restartRequired ? " · äußerer Neustart im Panel nötig" : ""}{host?.worldName ? ` · Host-Welt ${host.worldName}` : ""}</p>
        <ul>{(host?.notes ?? []).map((note) => <li key={note}>{note}</li>)}</ul>
        {host?.heap && host.heap.length > 0 && <p className="muted">{host.heap.map((flag) => `${flag.name}: ${flag.detail}`).join(" · ")}</p>}
        {host?.message && <p>{host.message}</p>}
        <div className="setup-actions">
          <button className="btn" disabled={busy} type="button" onClick={() => void hostAction("apply", host?.confirmation || "HOST MINIMIEREN")}>Minimierung vorbereiten …</button>
          <button className="btn" disabled={busy || host?.status === "inactive"} type="button" onClick={() => void hostAction("rollback", host?.rollbackConfirmation || "HOST ZURUECK")}>Dateien zurückholen …</button>
        </div>
      </div>
    </section>
  );
}
