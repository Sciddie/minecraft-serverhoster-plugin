import { useEffect, useState } from "react";
import { dashboardRequest, jsonBody } from "../lib/hostApi";
import BackupSettings, { loadBackupSettings, saveBackupSettings, type BackupOptions, type Credentials, type Settings } from "./BackupSettings";
import VersionSelect, { useVersionCatalog, versionIsValid } from "./VersionSelect";

export interface SetupUpgrade {
  from: string;
  version: string;
  childPresent: boolean;
  childPath: string;
  askServer: boolean;
  askBackup: boolean;
  askSchedule: boolean;
  askNtfy: boolean;
  type: string;
  mcVersion: string;
  port: number;
}

export interface SetupState {
  required: boolean;
  completed: boolean;
  ntfy: { enabled: boolean; server: string; topic: string };
  upgrade?: SetupUpgrade;
}

function stepIsVisible(index: number, upgrade?: SetupUpgrade, choice?: "path" | "new" | null) {
  if (!upgrade) return true;
  if (!upgrade.childPresent) {
    if (choice === "new") return true;
    if (choice === "path") {
      if (index === 0 || index === 5) return true;
      if (index === 1) return upgrade.askServer;
      if (index === 2) return upgrade.askBackup || upgrade.askSchedule;
      if (index === 3) return upgrade.askNtfy;
      return false;
    }
    return index === 0;
  }
  if (index === 0 || index === 4) return false;
  if (index === 1) return upgrade.askServer;
  if (index === 2) return upgrade.askBackup || upgrade.askSchedule;
  if (index === 3) return upgrade.askNtfy;
  return true;
}

function firstVisibleStep(upgrade?: SetupUpgrade, choice?: "path" | "new" | null) {
  for (let index = 0; index <= 5; index += 1) if (stepIsVisible(index, upgrade, choice)) return index;
  return 5;
}

function updateOnly(upgrade?: SetupUpgrade) {
  return !!upgrade && upgrade.childPresent && !upgrade.askServer && !upgrade.askBackup && !upgrade.askSchedule && !upgrade.askNtfy;
}

export interface SetupHostState {
  status: string;
  firstSetupStatus: string;
  firstSetupConfirmation: string;
  confirmation: string;
}

const newServerDescription = "Einen neuen Child-Server in ./child-server einrichten.";

export default function SetupWizard({ state, host, onComplete }: {
  state: SetupState;
  host: SetupHostState | null;
  onComplete: () => Promise<boolean>;
}) {
  const upgrade = state.upgrade;
  const knownType = upgrade && ["fabric", "paper", "custom"].includes(upgrade.type) ? upgrade.type : "fabric";
  const [childChoice, setChildChoice] = useState<"path" | "new" | null>(null);
  const [adoptPath, setAdoptPath] = useState("");
  const [pathError, setPathError] = useState("");
  const [checkingPath, setCheckingPath] = useState(false);
  const [step, setStep] = useState(() => firstVisibleStep(upgrade, null));
  const [directory] = useState("./child-server");
  const [type, setType] = useState(knownType);
  const [mcVersion, setMcVersion] = useState(knownType === upgrade?.type ? (upgrade?.mcVersion || "") : "");
  const [port, setPort] = useState(upgrade ? String(upgrade.port || 2063) : "2063");
  const [ntfy, setNtfy] = useState(state.ntfy);
  const [minimize, setMinimize] = useState(false);
  const [backupSettings, setBackupSettings] = useState<Settings | null>(null);
  const [credentials, setCredentials] = useState<Credentials>({ username: "", password: "", passwordSet: false });
  const [backupOptions, setBackupOptions] = useState<BackupOptions>({ paths: ["world"], keep: 5 });
  const [schedule, setSchedule] = useState(false);
  const [backupTime, setBackupTime] = useState("05:00");
  const [scheduleId] = useState(() => `onboarding-${globalThis.crypto?.randomUUID?.() ?? Math.random().toString(36).slice(2, 14)}`);
  const [scheduleCreated, setScheduleCreated] = useState(false);
  const [backupTest, setBackupTest] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const catalog = useVersionCatalog(type, step === 1);
  const creatingNew = !upgrade || childChoice === "new";
  const path = creatingNew ? directory.trim() : childChoice === "path" ? adoptPath.trim().replaceAll("\\", "/") : (upgrade?.childPath || directory);
  const allLabels = ["Ausgangslage", "Server", "Backups", "Benachrichtigungen", "Host", "Prüfen"];
  const shownSteps = allLabels.flatMap((label, index) => stepIsVisible(index, upgrade, childChoice) ? [{ label, index }] : []);
  const previousStep = (() => {
    let index = step - 1;
    while (index >= 0 && !stepIsVisible(index, upgrade, childChoice)) index -= 1;
    return index;
  })();
  const move = (direction: 1 | -1) => {
    setError("");
    let nextStep = step + direction;
    while (nextStep >= 0 && nextStep <= 5 && !stepIsVisible(nextStep, upgrade, childChoice)) nextStep += direction;
    if (nextStep >= 0 && nextStep <= 5) setStep(nextStep);
  };
  useEffect(() => {
    loadBackupSettings().then(({ settings, credentials: loaded, options }) => {
      setBackupSettings(settings);
      setCredentials(loaded);
      setBackupOptions(options);
    }).catch((cause) => setError(cause instanceof Error ? cause.message : "Backup-Einstellungen konnten nicht geladen werden."));
  }, []);
  const next = async () => {
    setError("");
    if (step === 0 && upgrade && !upgrade.childPresent) {
      if (childChoice !== "path" && childChoice !== "new") {
        setError("Bitte wähle einen vorhandenen Ordner oder einen neuen Server.");
        return;
      }
      if (childChoice === "path") {
        setCheckingPath(true);
        try {
          const result = await dashboardRequest<{ present: boolean; error?: string }>("/api/setup/child", {
            method: "POST",
            body: jsonBody({ path: adoptPath.trim() }),
          });
          if (!result.present) {
            setPathError(result.error || "In diesem Ordner wurde kein Child gefunden");
            return;
          }
          setPathError("");
        } catch (cause) {
          setPathError(cause instanceof Error ? cause.message : "Der Ordner konnte nicht geprüft werden.");
          return;
        } finally {
          setCheckingPath(false);
        }
      }
    }
    if (step === 1) {
      if (!path) {
        setError("Bitte gib einen Serverordner an.");
        return;
      }
      if (!versionIsValid(type, mcVersion, catalog.versions, !!upgrade && childChoice !== "new" && knownType === upgrade.type) ||
          !Number.isInteger(Number(port)) || Number(port) < 1 || Number(port) > 65535) {
        setError("Bitte gib eine Minecraft-Version und einen gültigen Spielport von 1 bis 65535 an.");
        return;
      }
    }
    if (step === 2 && ((creatingNew || upgrade?.askBackup) && !backupSettings || ((creatingNew || upgrade?.askSchedule) && schedule && !/^\d{2}:\d{2}$/.test(backupTime)))) {
      setError("Bitte prüfe die Backup-Einstellungen und die Uhrzeit.");
      return;
    }
    if (step === 3 && ntfy.enabled && (!ntfy.server.trim() || !ntfy.topic.trim())) {
      setError("Bitte gib den ntfy-Server und ein Thema an oder deaktiviere die Benachrichtigungen.");
      return;
    }
    setStep((current) => {
      let nextStep = current + 1;
      while (nextStep <= 5 && !stepIsVisible(nextStep, upgrade, childChoice)) nextStep += 1;
      return nextStep;
    });
  };

  const finish = async () => {
    setBusy(true);
    setError("");
    try {
      let minimizeConfirmation = "";
      if (minimize) {
        const required = host?.confirmation || "HOST MINIMIEREN";
        const accepted = await window.dashboardUi?.confirm({
          title: "Host-Minimierung vorbereiten?",
          description: "Die Host-Einstellungen werden gesichert und minimiert. Welten, Child-Daten und Backups werden nicht gelöscht. Anschließend ist ein Host-Neustart im Panel nötig.",
          confirmLabel: "Minimierung bestätigen",
        });
        if (!accepted) return;
        minimizeConfirmation = required;
      }
      if (creatingNew || upgrade?.askBackup) {
        if (!backupSettings) throw new Error("Backup-Einstellungen wurden nicht geladen.");
        await saveBackupSettings(backupSettings, credentials, backupOptions);
        if (backupSettings.backupStorage === "remote") setCredentials({ ...credentials, password: "", passwordSet: true });
      }
      const createSchedule = async () => {
        if (upgrade && childChoice !== "new" && !upgrade.askSchedule) return;
        if (!schedule || scheduleCreated) return;
        const [hour, minute] = backupTime.split(":").map(Number);
        await dashboardRequest("/api/schedules", {
          method: "POST",
          body: jsonBody({
            id: scheduleId, name: "Tägliches Backup", enabled: true, interval: "DAILY",
            intervalValue: hour, timeValue: minute,
            timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC",
            actions: [{ id: `${scheduleId}-backup`, type: "backup", command: "" }],
          }),
        });
        setScheduleCreated(true);
      };
      await createSchedule();
      const result = await dashboardRequest<{ warning?: string }>("/api/setup", {
        method: "POST",
        body: jsonBody({ action: creatingNew ? "new" : "update", path, type, mcVersion, port: Number(port), ntfy }),
      });
      let notice = result.warning ?? (!creatingNew
        ? `Update ${upgrade?.version || "2.5.0"} ist übernommen. Der Child kann jetzt gestartet werden.`
        : "Einrichtung gespeichert. Der Child kann jetzt gestartet werden.");
      let warning = !!result.warning;
      if (minimizeConfirmation) {
        try {
          const minimized = await dashboardRequest<{ message?: string }>("/api/host-minimize", {
            method: "POST",
            body: jsonBody({ action: "apply", confirm: minimizeConfirmation }),
          });
          notice = `Einrichtung gespeichert. ${minimized.message ?? "Host-Minimierung vorbereitet; starte den Host im Panel neu."}`;
        } catch (cause) {
          warning = true;
          notice = `Einrichtung gespeichert, aber Host-Minimierung fehlgeschlagen: ${cause instanceof Error ? cause.message : String(cause)}. Du kannst sie unter Einrichtung erneut versuchen.`;
        }
      }
      if (!await onComplete()) {
        warning = true;
        notice += " Der aktuelle Einrichtungsstatus konnte nicht neu geladen werden. Bitte lade das Dashboard neu.";
      }
      window.dispatchEvent(new CustomEvent("dashboard:setup-required", { detail: false }));
      window.dashboardUi?.navigate("status");
      window.dashboardUi?.notify(notice, warning);
    } catch (cause) {
      const message = cause instanceof Error ? cause.message : "Einrichtung fehlgeschlagen.";
      if (upgrade && !upgrade.childPresent && childChoice === "path" && /Ordner|Child/.test(message)) {
        setStep(0);
        setPathError(message);
      } else setError(message);
    } finally {
      setBusy(false);
    }
  };

  const runFirstSetup = async () => {
    const required = host?.firstSetupConfirmation || "HOST GRUNDSETUP";
    const accepted = await window.dashboardUi?.confirm({
      title: "Einmalige Host-Grundeinrichtung ausführen?",
      description: "Erkannte äußere Minecraft-Welten werden unwiderruflich gelöscht und andere Host-Plugin-JARs deaktiviert. Child-Daten, Konfiguration und Backups bleiben erhalten. Der Host fährt herunter und muss nach der Bestätigung und nach der Vorbereitung im Panel gestartet werden. Noch nicht gespeicherte Eingaben in diesem Assistenten gehen verloren.",
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
      window.dashboardUi?.notify("Der Host fährt herunter. Bitte folge den Neustart-Hinweisen im Panel.");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Host-Grundeinrichtung konnte nicht gestartet werden.");
      setBusy(false);
    }
  };

  if (updateOnly(upgrade)) {
    const updateTitle = `Update ${upgrade?.version || "2.5.0"}`;
    return (
      <section className="onboarding">
        <header className="onboarding-header">
          <p className="eyebrow">SERVERHOSTER · ERSTE SCHRITTE</p>
          <h1>{updateTitle}</h1>
          <p>Das Update ist da. Alle bisherigen Angaben bleiben erhalten. Es wird noch kein Child gestartet.</p>
          <ol className="onboarding-progress" aria-label="Einrichtungsschritte">
            <li aria-current="step"><span>1</span>Update</li>
          </ol>
        </header>
        <div className="setup-card-list onboarding-panel">
          <h2>{updateTitle}</h2>
          <p>Child gefunden in {upgrade?.childPath}.</p>
          <p>In der bisherigen Konfiguration fehlt keine Angabe aus der Einrichtung. Der Child-Ordner bleibt {upgrade?.childPath}.</p>
          {error && <p className="workflow-error" role="alert">{error}</p>}
          <div className="onboarding-actions">
            <button className="btn pri" type="button" disabled={busy} onClick={() => void finish()}>{busy ? "Speichere …" : "Update abschließen"}</button>
          </div>
        </div>
      </section>
    );
  }

  return (
    <section className="onboarding">
      <header className="onboarding-header">
        <p className="eyebrow">SERVERHOSTER · ERSTE SCHRITTE</p>
        <h1>{creatingNew ? "Richten wir deinen Server ein" : upgrade?.childPresent ? "Fehlende Angaben ergänzen" : `Update ${upgrade?.version || "2.5.0"}`}</h1>
        <p>{creatingNew
          ? "Wir führen dich durch die Entscheidungen, die für deine Situation nötig sind. Es wird noch kein Child gestartet."
          : upgrade?.childPresent
            ? `Child gefunden in ${upgrade.childPath}. Nur Angaben, die in der bisherigen Konfiguration fehlen. Alles andere bleibt. Es wird noch kein Child gestartet.`
            : `In ${upgrade?.childPath} wurde kein Child gefunden. Gib den Ordner an, in dem er liegt, oder erstelle einen neuen Server. Es wird noch kein Child gestartet.`}</p>
        <ol className="onboarding-progress" aria-label="Einrichtungsschritte">
          {shownSteps.map((item, position) => <li key={item.label} aria-current={item.index === step ? "step" : undefined}><span>{position + 1}</span>{item.label}</li>)}
        </ol>
      </header>
      <div className="setup-card-list onboarding-panel">
        {step === 0 && upgrade && !upgrade.childPresent && <>
          <h2>Wo liegt der Child?</h2>
          <p>In {upgrade.childPath} wurde kein Child gefunden. Ein Child ist ein Ordner mit server.properties oder einer JAR-Datei.</p>
          <label className="setup-card onboarding-choice">
            <input type="radio" name="setup-action" checked={childChoice === "path"} onChange={() => { setChildChoice("path"); setMinimize(false); setError(""); }} />
            <span><strong>Vorhandenen Ordner verwenden</strong><small>Pfad zum Ordner angeben, in dem der Child schon liegt.</small></span>
          </label>
          {childChoice === "path" && <label>Serverordner
            <input value={adoptPath} placeholder="./child-server" onChange={(event) => { setAdoptPath(event.target.value); setPathError(""); }} />
          </label>}
          {childChoice === "path" && pathError && <p className="login-error" role="alert">{pathError}</p>}
          <label className="setup-card onboarding-choice">
            <input type="radio" name="setup-action" checked={childChoice === "new"} onChange={() => { setChildChoice("new"); setPathError(""); setError(""); }} />
            <span><strong>Neuen Server erstellen</strong><small>{newServerDescription}</small></span>
          </label>
        </>}
        {step === 0 && !upgrade && <>
          <h2>Was möchtest du einrichten?</h2>
          <p>Der Child-Server wird in ./child-server eingerichtet.</p>
          <label className="setup-card onboarding-choice">
            <input type="radio" name="setup-action" checked readOnly />
            <span><strong>Neuen Server erstellen</strong><small>{newServerDescription}</small></span>
          </label>
        </>}
        {step === 1 && <>
          <h2>{creatingNew ? "Neuen Server konfigurieren" : "Fehlende Serverangaben"}</h2>
          <p>{creatingNew ? newServerDescription : `Der vorhandene Child bleibt in ${path}.`}</p>
          <label>Serverordner<input value={path} readOnly /></label>
          <div className="setup-form-grid">
            <label>Servertyp<select value={type} onChange={(event) => { setType(event.target.value); setMcVersion(""); }}>
              {["fabric", "paper", "custom"].map((value) => <option key={value}>{value}</option>)}
            </select></label>
            <VersionSelect type={type} value={mcVersion} onChange={setMcVersion} preserve={!!upgrade && childChoice !== "new" && knownType === upgrade.type} catalog={catalog} />
            <label>Spielport<input type="number" min="1" max="65535" value={port} onChange={(event) => setPort(event.target.value)} /></label>
          </div>
          <p className="muted">Der Spielport muss beim Hoster freigegeben sein und sich vom Host- und Dashboard-Port unterscheiden.</p>
        </>}
        {step === 2 && <>
          <h2>Backups einrichten</h2>
          <p>{creatingNew || upgrade?.askBackup ? "Wähle ein Speicherziel und die Dateien, die gesichert werden sollen." : "Für die vorhandene Sicherung kann ein täglicher Zeitplan ergänzt werden."}</p>
          {(creatingNew || upgrade?.askBackup) && backupSettings && <BackupSettings settings={backupSettings} credentials={credentials} options={backupOptions} onSettings={setBackupSettings} onCredentials={setCredentials} onOptions={setBackupOptions} />}
          {(creatingNew || upgrade?.askSchedule) && <label className="setup-checkbox"><input type="checkbox" checked={schedule} disabled={scheduleCreated} onChange={(event) => setSchedule(event.target.checked)} /> Tägliches automatisches Backup einrichten</label>}
          {(creatingNew || upgrade?.askSchedule) && schedule && <label>Uhrzeit ({Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC"})
            <input type="time" value={backupTime} disabled={scheduleCreated} onChange={(event) => setBackupTime(event.target.value)} />
          </label>}
          <p className="muted">Geplante Backups warten auf einen leeren Server und stoppen den Child für die Sicherung. Ohne Zeitplan erfolgen Backups nur manuell.</p>
          {(creatingNew || upgrade?.askBackup) && (
            <button className="btn" type="button" disabled={busy || !backupSettings} onClick={() => void (async () => {
              if (!backupSettings) return;
              setBusy(true); setError(""); setBackupTest("");
              try {
                await saveBackupSettings(backupSettings, credentials, backupOptions);
                if (backupSettings.backupStorage === "remote") setCredentials({ ...credentials, password: "", passwordSet: true });
                const result = await dashboardRequest<{ transport: string }>("/api/backup/test", { method: "POST" });
                setBackupTest(`Zieltest: ${result.transport}. Die Backup-Einstellungen wurden gespeichert.`);
              } catch (cause) {
                setError(cause instanceof Error ? cause.message : "Backupziel konnte nicht getestet werden.");
              } finally { setBusy(false); }
            })()}>Einstellungen speichern und Ziel testen</button>
          )}
          {backupTest && <p role="status">{backupTest}</p>}
        </>}
        {step === 3 && <>
          <h2>Benachrichtigungen</h2>
          <p>Optional kannst du Warnungen und Wiederherstellungsmeldungen über ntfy erhalten. Das lässt sich später ändern.</p>
          <label className="setup-checkbox"><input type="checkbox" checked={ntfy.enabled}
            onChange={(event) => setNtfy({ ...ntfy, enabled: event.target.checked })} /> ntfy aktivieren</label>
          {ntfy.enabled && <div className="setup-form-grid">
            <label>ntfy-Server-URL<input value={ntfy.server} onChange={(event) => setNtfy({ ...ntfy, server: event.target.value })} /></label>
            <label>Thema<input value={ntfy.topic} onChange={(event) => setNtfy({ ...ntfy, topic: event.target.value })} /></label>
          </div>}
          {ntfy.enabled && <p className="muted">Ein optionales Zugangstoken bleibt in der lokalen secrets.yml.</p>}
        </>}
        {step === 4 && <>
          <h2>Äußeren Host vorbereiten</h2>
          <p>ServerHoster läuft auf einem äußeren Spigot-Host. Dessen Einstellungen können unabhängig vom Child minimiert werden.</p>
          <label className="setup-checkbox"><input type="checkbox" checked={minimize}
            onChange={(event) => setMinimize(event.target.checked)} /> Host-Minimierung nach dem Speichern vorbereiten</label>
          <p className="muted">Die Minimierung sichert Host-Dateien, löscht keine Welten und benötigt einen anschließenden Host-Neustart im Panel. Die maximale Host-Heapgröße bleibt unverändert.</p>
          {host?.firstSetupStatus === "manual-required" && <div>
            <h3>Einmalige Grundeinrichtung für ein Upgrade</h3>
            <p>Optional: Void-Lobby vorbereiten, andere Host-Plugins deaktivieren und alte Host-Welten endgültig entfernen. Das ist nicht rückgängig zu machen. Der Host fährt herunter und muss im Panel mehrfach gestartet werden.</p>
            <button className="btn" type="button" disabled={busy} onClick={() => void runFirstSetup()}>Einmalige Grundeinrichtung starten …</button>
          </div>}
          {host?.firstSetupStatus === "restart-required" && <p>Die einmalige Grundeinrichtung wartet auf einen Host-Neustart im Panel.</p>}
        </>}
        {step === 5 && <>
          <h2>Alles prüfen</h2>
          <dl className="onboarding-summary">
            <dt>Vorhaben</dt><dd>{creatingNew ? "Neuen Server erstellen" : childChoice === "path" ? "Vorhandenen Ordner verwenden" : "Fehlende Angaben ergänzen"}</dd>
            <dt>Serverordner</dt><dd>{path}</dd>
            <dt>Server</dt><dd>{`${type} ${mcVersion} · Port ${port}`}</dd>
            <dt>Benachrichtigungen</dt><dd>{ntfy.enabled ? `ntfy · ${ntfy.server} · ${ntfy.topic}` : "Ausgeschaltet"}</dd>
            <dt>Backups</dt><dd>{!creatingNew && upgrade && !upgrade.askBackup ? "Unverändert" : backupSettings?.backupStorage === "remote" ? `FTP/FTPS · ${backupSettings.backupHost}` : "Lokal"} · {!creatingNew && upgrade && !upgrade.askSchedule ? "Zeitplan unverändert" : schedule ? `täglich ${backupTime}` : "nur manuell"}</dd>
            <dt>Backup-Pfade</dt><dd>{!creatingNew && upgrade && !upgrade.askBackup ? "Unverändert" : `${backupOptions.paths.join(", ")} · ${backupOptions.keep === 0 ? "unbegrenzt" : `${backupOptions.keep} Backups behalten`}`}</dd>
            <dt>Host-Minimierung</dt><dd>{minimize ? "Nach Bestätigung vorbereiten" : "Unverändert lassen"}</dd>
          </dl>
          <p>{creatingNew ? newServerDescription : `Der Child-Ordner bleibt ${path}.`} Beim Speichern wird noch kein Server-JAR heruntergeladen oder Child gestartet.</p>
          <p>Die Host-Aktionen kannst du auch später unter „Einrichtung“ verwalten.</p>
        </>}
        {error && <p className="workflow-error" role="alert">{error}</p>}
        <div className="onboarding-actions">
          {previousStep >= 0 && <button className="btn" type="button" disabled={busy || checkingPath} onClick={() => move(-1)}>Zurück</button>}
          {step < 5
            ? <button className="btn pri" type="button" disabled={busy || checkingPath} onClick={() => void next()}>{checkingPath ? "Prüfe …" : "Weiter"}</button>
            : <button className="btn pri" type="button" disabled={busy || checkingPath} onClick={() => void finish()}>{busy ? "Speichere …" : "Einrichtung abschließen"}</button>}
        </div>
      </div>
    </section>
  );
}
