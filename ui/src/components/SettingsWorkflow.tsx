import { useCallback, useEffect, useState } from "react";
import { ArrowClockwise, FloppyDisk, ShieldWarning } from "@phosphor-icons/react";
import BackupSettings, { loadBackupSettings, saveBackupSettings, type BackupOptions, type Credentials, type Settings } from "./BackupSettings";
import { dashboardRequest, jsonBody } from "../lib/hostApi";

const defaults: Settings = {
  autoRestartEnabled: true,
  maxRestartAttempts: 5,
  initialRestartDelaySeconds: 5,
  maximumRestartDelaySeconds: 300,
  restartResetAfterSeconds: 600,
  xms: "2G",
  xmx: "5G",
  dashboardPort: 2064,
  httpsEnabled: false,
  backupStorage: "local",
  backupProtocol: "AUTO",
  backupHost: "",
  backupPort: 21,
  backupDirectory: "ServerHoster",
};

type Section = "memory" | "dashboard" | "https" | "restart" | "backup";

export default function SettingsWorkflow() {
  const [settings, setSettings] = useState<Settings>(defaults);
  const [credentials, setCredentials] = useState<Credentials>({ username: "", password: "", passwordSet: false });
  const [options, setOptions] = useState<BackupOptions>({ paths: ["world", "config", "mods"], keep: 5 });
  const [loaded, setLoaded] = useState(false);
  const [loading, setLoading] = useState(false);
  const [savingSection, setSavingSection] = useState<Section | null>(null);
  const [error, setError] = useState("");

  const refresh = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const loadedSettings = await loadBackupSettings();
      setSettings(loadedSettings.settings);
      setCredentials(loadedSettings.credentials);
      setOptions(loadedSettings.options);
      setLoaded(true);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Einstellungen konnten nicht geladen werden.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    const onPageChange = (event: Event) => {
      if ((event as CustomEvent<string>).detail === "settings") void refresh();
    };
    window.addEventListener("dashboard:tab", onPageChange);
    if (!document.getElementById("app")?.classList.contains("hide")
        && !document.getElementById("t-settings")?.classList.contains("hide")) void refresh();
    return () => window.removeEventListener("dashboard:tab", onPageChange);
  }, [refresh]);

  const update = <K extends keyof Settings>(key: K, value: Settings[K]) =>
    setSettings((current) => ({ ...current, [key]: value }));

  const saveSection = async (section: Section, body: Record<string, unknown>, after?: () => Promise<void>) => {
    setSavingSection(section);
    setError("");
    try {
      const saved = await dashboardRequest<{ settings: Settings }>("/api/settings", {
        method: "POST",
        body: jsonBody({ section, ...body }),
      });
      if (after) await after();
      setSettings((current) => ({ ...current, ...pick(saved.settings, section) }));
      window.dashboardUi?.notify("Gespeichert.");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Einstellungen konnten nicht gespeichert werden.");
    } finally {
      setSavingSection(null);
    }
  };

  const saveBackup = async () => {
    setSavingSection("backup");
    setError("");
    try {
      await saveBackupSettings(settings, credentials, options);
      const loadedSettings = await loadBackupSettings();
      setSettings((current) => ({ ...current, ...pick(loadedSettings.settings, "backup") }));
      setCredentials(loadedSettings.credentials);
      setOptions(loadedSettings.options);
      window.dashboardUi?.notify("Gespeichert.");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Einstellungen konnten nicht gespeichert werden.");
    } finally {
      setSavingSection(null);
    }
  };

  const busy = loading || savingSection !== null;

  return (
    <section className="feature-workflow">
      <header className="feature-heading-card">
        <div className="feature-heading-copy">
          <span className="feature-icon"><ShieldWarning size={23} weight="fill" /></span>
          <div><h2>Einstellungen</h2><p>Jeder Abschnitt wird für sich gespeichert.</p></div>
        </div>
        <div className="workflow-action-group">
          <button className="btn" disabled={busy} onClick={() => void refresh()} type="button">
            <ArrowClockwise size={17} /> Aktualisieren
          </button>
        </div>
      </header>

      {error && <p className="workflow-error" role="alert">{error}</p>}

      <section className="setup-card-list">
        <div className="row">
          <h3 style={{ flex: 1 }}>RAM</h3>
          <button className="btn pri" type="button" disabled={!loaded || busy} onClick={() => void saveSection("memory", { xms: settings.xms.trim(), xmx: settings.xmx.trim() })}>
            <FloppyDisk size={17} /> {savingSection === "memory" ? "Speichere …" : "Speichern"}
          </button>
        </div>
        <p className="muted">Wirkt beim nächsten Child-Start.</p>
        <div className="setup-form-grid">
          <label>RAM Start (xms)
            <input value={settings.xms} onChange={(event) => update("xms", event.target.value)} />
          </label>
          <label>RAM Maximum (xmx)
            <input value={settings.xmx} onChange={(event) => update("xmx", event.target.value)} />
          </label>
        </div>
      </section>

      <section className="setup-card-list">
        <div className="row">
          <h3 style={{ flex: 1 }}>Dashboard-Port</h3>
          <button className="btn pri" type="button" disabled={!loaded || busy} onClick={() => void saveSection("dashboard", { dashboardPort: settings.dashboardPort })}>
            <FloppyDisk size={17} /> {savingSection === "dashboard" ? "Speichere …" : "Speichern"}
          </button>
        </div>
        <p className="muted">Wirkt nach einem Spigot-Neustart.</p>
        <label>Port
          <input type="number" min="1" max="65535" value={settings.dashboardPort}
            onChange={(event) => update("dashboardPort", Number(event.target.value))} />
        </label>
      </section>

      <section className="setup-card-list">
        <div className="row">
          <h3 style={{ flex: 1 }}>HTTPS</h3>
          <button className="btn pri" type="button" disabled={!loaded || busy} onClick={() => void saveSection("https", { httpsEnabled: settings.httpsEnabled })}>
            <FloppyDisk size={17} /> {savingSection === "https" ? "Speichere …" : "Speichern"}
          </button>
        </div>
        <p className="muted">Modus, Zertifikat und Bind bleiben in config.yml. Wirkt nach einem Spigot-Neustart.</p>
        <label className="setup-checkbox">
          <input type="checkbox" checked={settings.httpsEnabled} onChange={(event) => update("httpsEnabled", event.target.checked)} />
          HTTPS für das Dashboard aktivieren
        </label>
      </section>

      <section className="setup-card-list">
        <div className="row">
          <h3 style={{ flex: 1 }}>Automatische Neustarts</h3>
          <button className="btn pri" type="button" disabled={!loaded || busy} onClick={() => void saveSection("restart", {
            autoRestartEnabled: settings.autoRestartEnabled,
            maxRestartAttempts: settings.maxRestartAttempts,
            initialRestartDelaySeconds: settings.initialRestartDelaySeconds,
            maximumRestartDelaySeconds: settings.maximumRestartDelaySeconds,
            restartResetAfterSeconds: settings.restartResetAfterSeconds,
          })}>
            <FloppyDisk size={17} /> {savingSection === "restart" ? "Speichere …" : "Speichern"}
          </button>
        </div>
        <p className="muted">Nach wiederholten Fehlstarts pausiert ServerHoster die automatischen Neustarts und meldet den Zustand im Dashboard-Log sowie über ntfy, falls aktiviert. Der Spigot-Host wird nicht gestoppt.</p>
        <label className="setup-checkbox">
          <input
            type="checkbox"
            checked={settings.autoRestartEnabled}
            onChange={(event) => update("autoRestartEnabled", event.target.checked)}
          />
          Automatische Neustarts aktivieren
        </label>
        <div className="setup-form-grid">
          <label>Maximale Wiederholungen (1–100)
            <input type="number" min="1" max="100" value={settings.maxRestartAttempts}
              onChange={(event) => update("maxRestartAttempts", Number(event.target.value))} />
          </label>
          <label>Erste Wartezeit (Sekunden)
            <input type="number" min="1" max="3600" value={settings.initialRestartDelaySeconds}
              onChange={(event) => update("initialRestartDelaySeconds", Number(event.target.value))} />
          </label>
          <label>Maximale Wartezeit (Sekunden)
            <input type="number" min="1" max="86400" value={settings.maximumRestartDelaySeconds}
              onChange={(event) => update("maximumRestartDelaySeconds", Number(event.target.value))} />
          </label>
          <label>Fehlerzähler zurücksetzen nach (Sekunden)
            <input type="number" min="60" max="604800" value={settings.restartResetAfterSeconds}
              onChange={(event) => update("restartResetAfterSeconds", Number(event.target.value))} />
          </label>
        </div>
      </section>

      <section className="setup-card-list">
        <div className="row">
          <h3 style={{ flex: 1 }}>Backupziel</h3>
          <button className="btn pri" type="button" disabled={!loaded || busy} onClick={() => void saveBackup()}>
            <FloppyDisk size={17} /> {savingSection === "backup" ? "Speichere …" : "Speichern"}
          </button>
        </div>
        <BackupSettings settings={settings} credentials={credentials} options={options} onSettings={setSettings} onCredentials={setCredentials} onOptions={setOptions} />
      </section>
    </section>
  );
}

function pick(settings: Settings, section: Section): Partial<Settings> {
  switch (section) {
    case "memory":
      return { xms: settings.xms, xmx: settings.xmx };
    case "dashboard":
      return { dashboardPort: settings.dashboardPort };
    case "https":
      return { httpsEnabled: settings.httpsEnabled };
    case "restart":
      return {
        autoRestartEnabled: settings.autoRestartEnabled,
        maxRestartAttempts: settings.maxRestartAttempts,
        initialRestartDelaySeconds: settings.initialRestartDelaySeconds,
        maximumRestartDelaySeconds: settings.maximumRestartDelaySeconds,
        restartResetAfterSeconds: settings.restartResetAfterSeconds,
      };
    case "backup":
      return {
        backupStorage: settings.backupStorage,
        backupProtocol: settings.backupProtocol,
        backupHost: settings.backupHost,
        backupPort: settings.backupPort,
        backupDirectory: settings.backupDirectory,
      };
  }
}
