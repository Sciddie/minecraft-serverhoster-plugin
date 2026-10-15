import { useCallback, useEffect, useState } from "react";
import { ArrowClockwise, CheckCircle, FloppyDisk, ShieldWarning } from "@phosphor-icons/react";
import { dashboardRequest, jsonBody } from "../lib/hostApi";

interface Settings {
  autoRestartEnabled: boolean;
  maxRestartAttempts: number;
  initialRestartDelaySeconds: number;
  maximumRestartDelaySeconds: number;
  restartResetAfterSeconds: number;
  backupStorage: "local" | "remote";
  backupProtocol: "AUTO" | "FTP" | "FTPS";
  backupHost: string;
  backupPort: number;
  backupDirectory: string;
}

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
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");

  const refresh = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      setSettings(await dashboardRequest<Settings>("/api/settings"));
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
    return () => window.removeEventListener("dashboard:tab", onPageChange);
  }, [refresh]);

  const update = <K extends keyof Settings>(key: K, value: Settings[K]) =>
    setSettings((current) => ({ ...current, [key]: value }));

  const save = async () => {
    setSaving(true);
    setError("");
    try {
      const result = await dashboardRequest<{ settings: Settings }>("/api/settings", {
        method: "POST",
        body: jsonBody(settings),
      });
      setSettings(result.settings);
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
          <button className="btn pri" disabled={loading || saving} onClick={() => void save()} type="button">
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
        <h3>FTP/FTPS-Backupziel</h3>
        <p className="muted">Passwort und Benutzername werden nicht über das Dashboard übertragen. Sie bleiben lokal in <code>plugins/ServerhosterPlugin/secrets.yml</code>.</p>
        <div className="setup-form-grid">
          <label>Speicherziel
            <select value={settings.backupStorage} onChange={(event) => update("backupStorage", event.target.value as Settings["backupStorage"])}>
              <option value="local">Lokal</option>
              <option value="remote">FTP/FTPS</option>
            </select>
          </label>
          <label>Protokoll
            <select value={settings.backupProtocol} onChange={(event) => update("backupProtocol", event.target.value as Settings["backupProtocol"])}>
              <option value="AUTO">AUTO (FTPS bevorzugen)</option>
              <option value="FTPS">FTPS erzwingen</option>
              <option value="FTP">Unverschlüsseltes FTP</option>
            </select>
          </label>
          <label>FTP-Server
            <input value={settings.backupHost} onChange={(event) => update("backupHost", event.target.value)} placeholder="ftp.example.net" />
          </label>
          <label>Port
            <input type="number" min="1" max="65535" value={settings.backupPort}
              onChange={(event) => update("backupPort", Number(event.target.value))} />
          </label>
          <label>Verzeichnis
            <input value={settings.backupDirectory} onChange={(event) => update("backupDirectory", event.target.value)} />
          </label>
        </div>
        <p className="muted"><CheckCircle size={15} /> FTP-Zugangsdaten bleiben getrennt von config.yml. Unverschlüsseltes FTP überträgt Zugangsdaten und Daten im Klartext.</p>
      </section>
    </section>
  );
}
