import { dashboardRequest, jsonBody } from "../lib/hostApi";

export interface Settings {
  autoRestartEnabled: boolean;
  maxRestartAttempts: number;
  initialRestartDelaySeconds: number;
  maximumRestartDelaySeconds: number;
  restartResetAfterSeconds: number;
  xms: string;
  xmx: string;
  dashboardPort: number;
  httpsEnabled: boolean;
  backupStorage: "local" | "remote";
  backupProtocol: "AUTO" | "FTP" | "FTPS";
  backupHost: string;
  backupPort: number;
  backupDirectory: string;
}

export interface Credentials {
  username: string;
  password: string;
  passwordSet: boolean;
}
export interface BackupOptions {
  paths: string[];
  keep: number;
}

export async function loadBackupSettings(): Promise<{ settings: Settings; credentials: Credentials; options: BackupOptions }> {
  const [settings, credentials, options] = await Promise.all([
    dashboardRequest<Settings>("/api/settings"),
    dashboardRequest<{ username: string; passwordSet: boolean }>("/api/backup/credentials"),
    dashboardRequest<BackupOptions>("/api/backup/options"),
  ]);
  return { settings, credentials: { ...credentials, password: "" }, options };
}

export const defaultBackupPaths = ["world", "config", "mods"];

export async function saveBackupSettings(settings: Settings, credentials: Credentials, options: BackupOptions): Promise<void> {
  if (!options.paths.length || options.paths.some((path) => !path.trim()))
    throw new Error("Bitte mindestens einen Backup-Pfad angeben.");
  if (!Number.isInteger(options.keep) || options.keep < 0 || options.keep > 10000)
    throw new Error("Die Anzahl der Backups muss zwischen 0 und 10000 liegen.");
  if (settings.backupStorage === "remote") {
    if (!credentials.username.trim()) throw new Error("Bitte gib den FTP-Benutzernamen ein.");
    if (!credentials.password && !credentials.passwordSet) throw new Error("Bitte gib das FTP-Passwort ein.");
    if (location.protocol !== "https:" || settings.backupProtocol !== "FTPS") {
      const accepted = await window.dashboardUi?.confirm({
        title: "Unsichere Verbindung bestätigen",
        description: "Bei Dashboard-HTTP werden Zugangsdaten zum Dashboard unverschlüsselt übertragen. FTP und AUTO können Zugangsdaten und Backup-Daten unverschlüsselt zum Backupserver übertragen. FTPS und Dashboard-HTTPS schützen die jeweilige Verbindung.",
        confirmLabel: "OK",
      });
      if (!accepted) throw new Error("Speichern abgebrochen.");
    }
    await dashboardRequest("/api/backup/credentials", {
      method: "POST",
      body: jsonBody({
        username: credentials.username,
        ...(credentials.password ? { password: credentials.password } : {}),
      }),
    });
  }
  await dashboardRequest("/api/settings", {
    method: "POST",
    body: jsonBody({
      section: "backup",
      backupStorage: settings.backupStorage,
      backupProtocol: settings.backupProtocol,
      backupHost: settings.backupHost,
      backupPort: settings.backupPort,
      backupDirectory: settings.backupDirectory,
    }),
  });
  await dashboardRequest("/api/backup/options", { method: "POST", body: jsonBody(options) });
}

export default function BackupSettings({ settings, credentials, options, onSettings, onCredentials, onOptions }: {
  settings: Settings;
  credentials: Credentials;
  options: BackupOptions;
  onSettings: (settings: Settings) => void;
  onCredentials: (credentials: Credentials) => void;
  onOptions: (options: BackupOptions) => void;
}) {
  return <>
    <label>Speicherziel
      <select value={settings.backupStorage} onChange={(event) => onSettings({ ...settings, backupStorage: event.target.value as Settings["backupStorage"] })}>
        <option value="local">Lokal auf diesem Server</option>
        <option value="remote">Extern über FTP/FTPS</option>
      </select>
    </label>
    {settings.backupStorage === "local" ? (
      <p className="muted">Lokale Backups liegen auf demselben Server und schützen nicht vor dessen vollständigem Verlust.</p>
    ) : <>
      <div className="setup-form-grid">
        <label>Protokoll
          <select value={settings.backupProtocol} onChange={(event) => onSettings({ ...settings, backupProtocol: event.target.value as Settings["backupProtocol"] })}>
            <option value="FTPS">FTPS (verschlüsselt)</option>
            <option value="AUTO">AUTO (FTPS bevorzugen, FTP möglich)</option>
            <option value="FTP">FTP (unverschlüsselt)</option>
          </select>
        </label>
        <label>FTP-Server<input value={settings.backupHost} onChange={(event) => onSettings({ ...settings, backupHost: event.target.value })} placeholder="ftp.example.net" /></label>
        <label>Port<input type="number" min="1" max="65535" value={settings.backupPort} onChange={(event) => onSettings({ ...settings, backupPort: Number(event.target.value) })} /></label>
        <label>Verzeichnis<input value={settings.backupDirectory} onChange={(event) => onSettings({ ...settings, backupDirectory: event.target.value })} /></label>
        <label>Benutzername<input autoComplete="username" value={credentials.username} onChange={(event) => onCredentials({ ...credentials, username: event.target.value })} /></label>
        <label>Passwort<input type="password" autoComplete="new-password" value={credentials.password} onChange={(event) => onCredentials({ ...credentials, password: event.target.value })} placeholder={credentials.passwordSet ? "Unverändert lassen" : "FTP-Passwort"} /></label>
      </div>
      <p className="muted">Das Passwort wird nach dem Speichern nicht angezeigt. Leer lassen behält ein vorhandenes Passwort. FTPS ist empfohlen; AUTO kann auf unverschlüsseltes FTP zurückfallen.</p>
    </>}
    <div className="setup-form-grid">
      <label>Zu sichernde Child-Pfade (einer pro Zeile)
        <textarea value={options.paths.join("\n")} onChange={(event) => onOptions({ ...options, paths: event.target.value.split("\n") })} rows={3} />
      </label>
      <label>Wie viele Backups behalten? (0 = unbegrenzt)
        <input type="number" min="0" max="10000" value={options.keep} onChange={(event) => onOptions({ ...options, keep: Number(event.target.value) })} />
      </label>
    </div>
    <p className="muted">Die Pfade sind relativ zum Child-Ordner, z. B. „world“, „server.properties“ oder „mods“. Nicht angegebene Dateien werden nicht gesichert.</p>
  </>;
}
