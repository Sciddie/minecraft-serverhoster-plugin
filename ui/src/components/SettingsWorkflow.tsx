import { useCallback, useEffect, useState } from "react";
import { ArrowClockwise, FloppyDisk, ShieldWarning } from "@phosphor-icons/react";
import BackupSettings, { loadBackupSettings, saveBackupSettings, type BackupOptions, type Credentials, type Settings } from "./BackupSettings";

const defaults: Settings = {
  autoRestartEnabled: true,
  maxRestartAttempts: 5,
  initialRestartDelaySeconds: 5,
  maximumRestartDelaySeconds: 300,
  restartResetAfterSeconds: 600,
  backupStorage: "local",
  backupProtocol: "AUTO",
  backupHost: "",
  backupPort: 21,
  backupDirectory: "ServerHoster",
};

export default function SettingsWorkflow() {
  const [settings, setSettings] = useState<Settings>(defaults);
  const [credentials, setCredentials] = useState<Credentials>({ username: "", password: "", passwordSet: false });
  const [options, setOptions] = useState<BackupOptions>({ paths: ["world", "config", "mods"], keep: 5 });
  const [loaded, setLoaded] = useState(false);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");

  const refresh = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const loaded = await loadBackupSettings();
      setSettings(loaded.settings);
      setCredentials(loaded.credentials);
      setOptions(loaded.options);
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

  const save = async () => {
    setSaving(true);
    setError("");
    try {
      await saveBackupSettings(settings, credentials, options);
      const loaded = await loadBackupSettings();
      setSettings(loaded.settings);
      setCredentials(loaded.credentials);
      setOptions(loaded.options);
      window.dashboardUi?.notify("Einstellungen gespeichert.");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Einstellungen konnten nicht gespeichert werden.");
    } finally {
      setSaving(false);
    }
  };

  return (
    <section className="feature-workflow">
      <header className="feature-heading-card">
        <div className="feature-heading-copy">
          <span className="feature-icon"><ShieldWarning size={23} weight="fill" /></span>
          <div><h2>Einstellungen</h2><p>Selten geänderte Betriebs- und Verbindungsoptionen.</p></div>
        </div>
        <div className="workflow-action-group">
          <button className="btn" disabled={loading || saving} onClick={() => void refresh()} type="button">
            <ArrowClockwise size={17} /> Aktualisieren
          </button>
          <button className="btn pri" disabled={!loaded || loading || saving} onClick={() => void save()} type="button">
            <FloppyDisk size={17} /> {saving ? "Speichere …" : "Speichern"}
          </button>
        </div>
      </header>

      {error && <p className="workflow-error" role="alert">{error}</p>}

      <section className="setup-card-list">
        <h3>Automatische Neustarts</h3>
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
        <h3>Backupziel</h3>
        <BackupSettings settings={settings} credentials={credentials} options={options} onSettings={setSettings} onCredentials={setCredentials} onOptions={setOptions} />
      </section>
    </section>
  );
}
