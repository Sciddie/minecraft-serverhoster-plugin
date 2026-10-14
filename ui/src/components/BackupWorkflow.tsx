import { useCallback, useEffect, useState } from "react";
import {
  ArchiveIcon,
  ArrowClockwise,
  ArrowCounterClockwise,
  CalendarDots,
  CheckCircle,
  CloudArrowUp,
  DownloadSimple,
  HardDrives,
  Plus,
  ShieldWarning,
  Trash,
} from "@phosphor-icons/react";
import { dashboardRequest, formatBytes, jsonBody } from "../lib/hostApi";

interface BackupEntry {
  name: string;
  bytes: number;
  mtime: number;
}

interface BackupSnapshot {
  files: BackupEntry[];
  last: string;
  storage: string;
}

export default function BackupWorkflow() {
  const [snapshot, setSnapshot] = useState<BackupSnapshot>({ files: [], last: "", storage: "local" });
  const [loading, setLoading] = useState(false);
  const [working, setWorking] = useState("");
  const [error, setError] = useState("");

  const refresh = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      setSnapshot(await dashboardRequest<BackupSnapshot>("/api/backup"));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Backups konnten nicht geladen werden.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    const onPageChange = (event: Event) => {
      if ((event as CustomEvent<string>).detail === "backup") void refresh();
    };
    const onServerChange = () => void refresh();
    window.addEventListener("dashboard:tab", onPageChange);
    window.addEventListener("dashboard:server-changed", onServerChange);
    return () => {
      window.removeEventListener("dashboard:tab", onPageChange);
      window.removeEventListener("dashboard:server-changed", onServerChange);
    };
  }, [refresh]);

  const createBackup = async () => {
    const accepted = await window.dashboardUi?.confirm({
      title: "Backup jetzt erstellen?",
      description: "Es wird ein Backup für den ausgewählten Server erstellt. Der Lauf kann je nach Weltgröße etwas dauern.",
      confirmLabel: "Backup erstellen",
    });
    if (!accepted) return;

    setWorking("create");
    setError("");
    try {
      await dashboardRequest("/api/backup", { method: "POST" });
      window.dashboardUi?.notify("Backup wurde erstellt.");
      await refresh();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Backup konnte nicht erstellt werden.");
    } finally {
      setWorking("");
    }
  };

  const testTarget = async () => {
    setWorking("test");
    setError("");
    try {
      const result = await dashboardRequest<{ transport?: string }>("/api/backup/test", { method: "POST" });
      const message = result.transport || "Backup-Ziel erreichbar";
      const insecure = message.toLowerCase().includes("unverschlüsselt");
      window.dashboardUi?.notify(insecure ? `Warnung: ${message}` : message, insecure);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Backup-Ziel konnte nicht getestet werden.");
    } finally {
      setWorking("");
    }
  };

  const mutateBackup = async (backup: BackupEntry, operation: "restore" | "delete") => {
    const restoring = operation === "restore";
    const typedName = await window.dashboardUi?.prompt({
      title: restoring ? "Backup wiederherstellen?" : "Backup löschen?",
      description: restoring
        ? `Die Dateien des ausgewählten Servers werden aus „${backup.name}“ wiederhergestellt. Zum Bestätigen den Backup-Namen eingeben.`
        : `„${backup.name}“ wird dauerhaft gelöscht. Zum Bestätigen den Backup-Namen eingeben.`,
      placeholder: backup.name,
      requiredValue: backup.name,
      confirmLabel: restoring ? "Wiederherstellen" : "Dauerhaft löschen",
      destructive: true,
    });
    if (typedName !== backup.name) return;

    setWorking(`${operation}:${backup.name}`);
    setError("");
    try {
      const result = await dashboardRequest<{ files?: number }>("/api/backup/" + operation, {
        method: "POST",
        body: jsonBody({ name: backup.name, confirm: typedName }),
      });
      window.dashboardUi?.notify(
        restoring ? `${result.files ?? 0} Dateien wiederhergestellt.` : "Backup gelöscht.",
      );
      await refresh();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Die Backup-Aktion ist fehlgeschlagen.");
    } finally {
      setWorking("");
    }
  };

  const storageLabel = snapshot.storage.toLowerCase() === "remote" ? "Externes FTP/FTPS-Ziel" : "Lokaler Speicher";

  return (
    <div className="feature-workflow">
      <section className="feature-heading-card">
        <div className="feature-heading-copy">
          <span className="feature-icon"><ArchiveIcon size={23} weight="fill" /></span>
          <div>
            <h2>Backups</h2>
            <p>Erstellen, prüfen und verwalten — nur bei Bedarf.</p>
          </div>
        </div>
        <div className="workflow-action-group">
          <button className="btn" disabled={working !== ""} onClick={() => void testTarget()} type="button">
            <CloudArrowUp size={17} /> {working === "test" ? "Prüfe …" : "Ziel testen"}
          </button>
          <button className="btn pri" disabled={working !== ""} onClick={() => void createBackup()} type="button">
            <Plus size={17} weight="bold" /> {working === "create" ? "Erstellt …" : "Backup erstellen"}
          </button>
        </div>
      </section>

      <section className="backup-summary-grid">
        <div className="backup-summary-card">
          <span className="summary-icon"><HardDrives size={19} /></span>
          <div><span>Speicherziel</span><strong>{storageLabel}</strong></div>
        </div>
        <div className="backup-summary-card">
          <span className="summary-icon"><ArchiveIcon size={19} /></span>
          <div><span>Verfügbare Backups</span><strong>{snapshot.files.length}</strong></div>
        </div>
        <div className="backup-summary-card">
          <span className="summary-icon"><CalendarDots size={19} /></span>
          <div><span>Letztes Backup</span><strong>{snapshot.last || "Noch keines"}</strong></div>
        </div>
      </section>

      {snapshot.storage.toLowerCase() === "remote" && (
        <aside className="workflow-notice">
          <ShieldWarning size={19} />
          <div>
            <strong>Remote-Backup konfiguriert</strong>
            <p>Zugangsdaten liegen in secrets.yml. AUTO bevorzugt FTPS; Klartext-FTP wird beim Verbindungstest ausdrücklich ausgewiesen.</p>
          </div>
        </aside>
      )}

      <section className="workflow-toolbar">
        <div><strong>Backup-Archiv</strong><span className="muted"> · {snapshot.files.length} Einträge</span></div>
        <button className="btn" disabled={loading} onClick={() => void refresh()} type="button">
          <ArrowClockwise size={17} /> Aktualisieren
        </button>
      </section>

      {error && <p className="workflow-error" role="alert">{error}</p>}
      {loading && snapshot.files.length === 0 ? (
        <div className="workflow-empty">Backups werden geladen …</div>
      ) : snapshot.files.length === 0 ? (
        <div className="workflow-empty">
          <ArchiveIcon size={32} />
          <strong>Noch keine Backups vorhanden</strong>
          <span>Backups werden ausschließlich auf Anfrage oder durch einen konfigurierten Task erstellt.</span>
        </div>
      ) : (
        <div className="backup-card-list">
          {snapshot.files.map((backup) => {
            const busy = working.endsWith(`:${backup.name}`);
            const when = new Date(backup.mtime);
            return (
              <article className="backup-card" key={backup.name}>
                <span className="backup-card-icon"><ArchiveIcon size={22} /></span>
                <div className="backup-card-info">
                  <h3>{backup.name}</h3>
                  <div className="backup-card-meta">
                    <span><CalendarDots size={15} /> {Number.isNaN(when.getTime()) ? "Zeit unbekannt" : when.toLocaleString("de-DE")}</span>
                    <span><HardDrives size={15} /> {formatBytes(backup.bytes)}</span>
                  </div>
                </div>
                <div className="backup-card-actions">
                  <a className="btn" href={`/api/backup/download?name=${encodeURIComponent(backup.name)}&server=${encodeURIComponent((document.getElementById("srv") as HTMLSelectElement | null)?.value ?? "")}`}>
                    <DownloadSimple size={17} /> Herunterladen
                  </a>
                  <button className="btn" disabled={working !== ""} onClick={() => void mutateBackup(backup, "restore")} type="button">
                    <ArrowCounterClockwise size={17} /> {busy && working.startsWith("restore:") ? "Lädt …" : "Wiederherstellen"}
                  </button>
                  <button className="btn danger-outline" disabled={working !== ""} onClick={() => void mutateBackup(backup, "delete")} type="button">
                    <Trash size={17} /> {busy && working.startsWith("delete:") ? "Löscht …" : "Löschen"}
                  </button>
                </div>
              </article>
            );
          })}
        </div>
      )}
      {snapshot.files.length > 0 && <p className="backup-hint"><CheckCircle size={15} /> Wiederherstellen und Löschen verlangen zur Sicherheit den exakten Backup-Namen.</p>}
    </div>
  );
}
