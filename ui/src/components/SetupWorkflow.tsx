import { useCallback, useEffect, useState } from "react";
import { ArrowClockwise, Check } from "@phosphor-icons/react";
import { dashboardRequest, jsonBody } from "../lib/hostApi";
import SetupWizard from "./SetupWizard";

interface HostMinimizeState {
  status: string;
  restartRequired: boolean;
  worldName: string;
  firstSetupStatus: string;
  firstSetupWorld: string;
  firstSetupDisabledPluginCount: string;
  firstSetupConfirmation: string;
  prepareConfirmation: string;
  confirmation: string;
  rollbackConfirmation: string;
  notes: string[];
  message?: string;
  heap?: { name: string; applied: boolean; detail: string }[];
}

interface SetupState {
  required: boolean;
  completed: boolean;
  ntfy: { enabled: boolean; server: string; topic: string };
}

export default function SetupWorkflow() {
  const [state, setState] = useState<SetupState | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [host, setHost] = useState<HostMinimizeState | null>(null);

  const refresh = useCallback(async () => {
    setError("");
    try {
      const result = await dashboardRequest<SetupState>("/api/setup");
      setState(result);
      setHost(await dashboardRequest<HostMinimizeState>("/api/host-minimize"));
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

  const runHostPrepare = async (required: string) => {
    const accepted = await window.dashboardUi?.confirm({
      title: "Host vorbereiten?",
      description: "Void-Lobby und Minimierung werden zusammen vorbereitet. Ein Neustart im Panel lädt beides. Alte Host-Welten werden erst gelöscht, wenn die neue Lobby wirklich geladen ist. -Xmx bleibt unverändert.",
      confirmLabel: "Host vorbereiten",
      destructive: true,
    });
    if (!accepted) return;
    setBusy(true);
    setError("");
    try {
      const result = await dashboardRequest<HostMinimizeState>("/api/host-prepare", {
        method: "POST",
        body: jsonBody({ confirm: required }),
      });
      setHost(result);
      window.dashboardUi?.notify(result.message ?? "Vorbereitet. Starte den Host einmal im Panel neu.");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Host konnte nicht vorbereitet werden.");
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

      {!state?.completed && <p>Die Einrichtung ist noch nicht abgeschlossen. Lade das Dashboard neu, damit der Assistent wieder erscheint.</p>}

      {host?.firstSetupStatus === "manual-required" && <div className="setup-card-list">
        <h3>Host vorbereiten</h3>
        <p>Ein Lauf bereitet die Void-Lobby und die Minimierung vor. Ein Neustart im Panel lädt beides. Alte Host-Welten werden erst gelöscht, wenn die neue Lobby wirklich geladen ist. Andere Plugin-JARs werden in `.disabled` umbenannt. -Xmx bleibt unverändert.</p>
        <div className="setup-actions">
          <button className="btn" disabled={busy} type="button" onClick={() => void runHostPrepare(host.prepareConfirmation || "HOST VORBEREITEN")}>Host vorbereiten …</button>
        </div>
      </div>}
      {host?.firstSetupStatus === "restart-required" && <p>Die Lobby ist vorbereitet. Bitte den äußeren Host einmal im Panel neu starten. Alte Welten werden erst gelöscht, wenn diese Lobby geladen ist.</p>}

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
