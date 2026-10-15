import { useCallback, useEffect, useMemo, useState } from "react";
import {
  ArrowClockwise,
  CalendarDots,
  Clock,
  FloppyDisk,
  PencilSimple,
  Play,
  Plus,
  Sliders,
  TerminalWindow,
  Trash,
} from "@phosphor-icons/react";
import { dashboardRequest, jsonBody } from "../lib/hostApi";

type Interval = "HOURLY" | "DAILY" | "WEEKLY";
type ActionType = "backup" | "command";

interface ScheduleAction {
  id: string;
  type: ActionType;
  command: string;
}

interface Schedule {
  id: string;
  name: string;
  enabled: boolean;
  interval: Interval;
  intervalValue: number;
  timeValue: number;
  timezone: string;
  lastRun: number;
  actions: ScheduleAction[];
  cron: string;
  next: string;
  last: string;
}

interface ScheduleDraft {
  id: string;
  name: string;
  enabled: boolean;
  interval: Interval;
  intervalValue: number;
  timeValue: number;
  timezone: string;
  actions: ScheduleAction[];
}

interface LegacyTask {
  id: string;
  enabled: boolean;
  cron: string;
  timezone: string;
  action: string;
  command: string;
  next: string;
  last: string;
}

const weekdays = ["Sonntag", "Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag"];

function newId(): string {
  return globalThis.crypto?.randomUUID?.().replaceAll("-", "") ?? Math.random().toString(36).slice(2, 14);
}

function newDraft(): ScheduleDraft {
  return {
    id: newId(),
    name: "",
    enabled: true,
    interval: "DAILY",
    intervalValue: 5,
    timeValue: 0,
    timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC",
    actions: [{ id: newId(), type: "backup", command: "" }],
  };
}

function draftFrom(schedule: Schedule): ScheduleDraft {
  return {
    id: schedule.id,
    name: schedule.name,
    enabled: schedule.enabled,
    interval: schedule.interval,
    intervalValue: schedule.intervalValue,
    timeValue: schedule.timeValue,
    timezone: schedule.timezone,
    actions: schedule.actions.map((action) => ({ ...action })),
  };
}

function describeSchedule(schedule: Schedule): string {
  if (schedule.interval === "HOURLY") return `Jede Stunde um Minute ${String(schedule.intervalValue).padStart(2, "0")}`;
  if (schedule.interval === "DAILY") {
    return `Täglich um ${String(schedule.intervalValue).padStart(2, "0")}:${String(schedule.timeValue).padStart(2, "0")}`;
  }
  return `${weekdays[schedule.intervalValue]} um ${String(Math.floor(schedule.timeValue / 60)).padStart(2, "0")}:${String(schedule.timeValue % 60).padStart(2, "0")}`;
}

function dateLabel(value: string): string {
  if (!value) return "Noch nicht ausgeführt";
  const time = new Date(value);
  return Number.isNaN(time.getTime()) ? value : time.toLocaleString("de-DE");
}

function askBeforeDelete(name: string): Promise<boolean> {
  return window.dashboardUi?.confirm({
    title: "Zeitplan löschen?",
    description: `Der Zeitplan „${name}“ und seine Aktionen werden dauerhaft entfernt.`,
    confirmLabel: "Zeitplan löschen",
    destructive: true,
  }) ?? Promise.resolve(window.confirm(`Zeitplan „${name}“ dauerhaft löschen?`));
}

export default function TaskWorkflow() {
  const [schedules, setSchedules] = useState<Schedule[]>([]);
  const [legacyTasks, setLegacyTasks] = useState<LegacyTask[]>([]);
  const [loading, setLoading] = useState(false);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [draft, setDraft] = useState<ScheduleDraft | null>(null);
  const [filter, setFilter] = useState<"all" | "enabled" | "paused">("all");

  const refresh = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const [scheduleList, taskList] = await Promise.all([
        dashboardRequest<Schedule[]>("/api/schedules"),
        dashboardRequest<LegacyTask[]>("/api/tasks"),
      ]);
      setSchedules(scheduleList);
      setLegacyTasks(taskList);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Aufgaben konnten nicht geladen werden.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
    const onPageChange = (event: Event) => {
      if ((event as CustomEvent<string>).detail === "tasks") void refresh();
    };
    window.addEventListener("dashboard:tab", onPageChange);
    return () => window.removeEventListener("dashboard:tab", onPageChange);
  }, [refresh]);

  const visibleSchedules = useMemo(
    () => schedules.filter((schedule) => (
      filter === "all" || (filter === "enabled" ? schedule.enabled : !schedule.enabled)
    )),
    [filter, schedules],
  );

  const saveDraft = async () => {
    if (!draft) return;
    setBusyId(draft.id);
    setError("");
    try {
      await dashboardRequest<Schedule>(draft.id && schedules.some((item) => item.id === draft.id)
        ? `/api/schedules/${encodeURIComponent(draft.id)}`
        : "/api/schedules", {
        method: draft.id && schedules.some((item) => item.id === draft.id) ? "PUT" : "POST",
        body: jsonBody(draft),
      });
      setDraft(null);
      window.dashboardUi?.notify(`Zeitplan „${draft.name}“ gespeichert.`);
      await refresh();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Zeitplan konnte nicht gespeichert werden.");
    } finally {
      setBusyId(null);
    }
  };

  const toggleSchedule = async (schedule: Schedule) => {
    setBusyId(schedule.id);
    setError("");
    try {
      await dashboardRequest(`/api/schedules/${encodeURIComponent(schedule.id)}/toggle`, {
        method: "POST",
        body: jsonBody({ enabled: !schedule.enabled }),
      });
      await refresh();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Status konnte nicht geändert werden.");
    } finally {
      setBusyId(null);
    }
  };

  const runSchedule = async (schedule: Schedule) => {
    setBusyId(schedule.id);
    setError("");
    try {
      await dashboardRequest(`/api/schedules/${encodeURIComponent(schedule.id)}/run`, { method: "POST" });
      window.dashboardUi?.notify(`Zeitplan „${schedule.name}“ wurde gestartet.`);
      await refresh();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Zeitplan konnte nicht gestartet werden.");
    } finally {
      setBusyId(null);
    }
  };

  const deleteSchedule = async (schedule: Schedule) => {
    if (!await askBeforeDelete(schedule.name)) return;
    setBusyId(schedule.id);
    setError("");
    try {
      await dashboardRequest(`/api/schedules/${encodeURIComponent(schedule.id)}`, { method: "DELETE" });
      window.dashboardUi?.notify(`Zeitplan „${schedule.name}“ gelöscht.`);
      await refresh();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Zeitplan konnte nicht gelöscht werden.");
    } finally {
      setBusyId(null);
    }
  };

  const runLegacyTask = async (task: LegacyTask) => {
    setBusyId(task.id);
    setError("");
    try {
      await dashboardRequest(`/api/tasks/run?id=${encodeURIComponent(task.id)}`, { method: "POST" });
      window.dashboardUi?.notify(`Konfigurationsaufgabe „${task.id}“ wurde gestartet.`);
      await refresh();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Aufgabe konnte nicht gestartet werden.");
    } finally {
      setBusyId(null);
    }
  };

  const updateDraft = (update: Partial<ScheduleDraft>) => {
    setDraft((current) => current ? { ...current, ...update } : current);
  };

  return (
    <div className="feature-workflow">
      <section className="feature-heading-card">
        <div className="feature-heading-copy">
          <span className="feature-icon"><CalendarDots size={23} weight="fill" /></span>
          <div>
            <h2>Geplante Aufgaben</h2>
            <p>Erstelle Zeitpläne mit geordneten Aktionen und konfiguriere sie direkt im Dashboard.</p>
          </div>
        </div>
        <div className="workflow-action-group">
          <button className="btn" onClick={() => void refresh()} type="button" disabled={loading}>
            <ArrowClockwise size={17} /> Aktualisieren
          </button>
          <button className="btn pri" onClick={() => setDraft(newDraft())} type="button">
            <Plus size={17} /> Neuer Zeitplan
          </button>
        </div>
      </section>

      <aside className="workflow-notice">
        <Sliders size={19} />
        <div>
          <strong>Zeitpläne werden in config.yml gespeichert</strong>
          <p>Dashboard-Zeitpläne und der rohe Konfigurationseditor verwenden dieselbe Konfiguration. Bestehende Aufgaben aus der alten tasks-Liste werden unten weiterhin angezeigt.</p>
        </div>
        <button className="btn" onClick={() => window.dashboardUi?.navigate("config")} type="button">
          config.yml öffnen
        </button>
      </aside>

      <section className="workflow-toolbar">
        <div>
          <strong>{schedules.length} {schedules.length === 1 ? "Zeitplan" : "Zeitpläne"}</strong>
          <span className="muted"> · {schedules.filter((schedule) => schedule.enabled).length} aktiv</span>
        </div>
        <div className="segmented-control" aria-label="Zeitpläne filtern">
          {(["all", "enabled", "paused"] as const).map((value) => (
            <button
              aria-pressed={filter === value}
              className={filter === value ? "selected" : ""}
              key={value}
              onClick={() => setFilter(value)}
              type="button"
            >
              {{ all: "Alle", enabled: "Aktiv", paused: "Pausiert" }[value]}
            </button>
          ))}
        </div>
      </section>

      {error && <p className="workflow-error" role="alert">{error}</p>}
      {loading && schedules.length === 0 ? (
        <div className="workflow-empty">Zeitpläne werden geladen …</div>
      ) : visibleSchedules.length === 0 ? (
        <div className="workflow-empty">
          <CalendarDots size={32} />
          <strong>{schedules.length === 0 ? "Noch keine Zeitpläne eingerichtet" : "Keine Zeitpläne in diesem Filter"}</strong>
          <span>{schedules.length === 0 ? "Erstelle einen Zeitplan, um Aktionen wie Backups oder Konsolenbefehle automatisch auszuführen." : "Wähle einen anderen Filter, um weitere Zeitpläne zu sehen."}</span>
          {schedules.length === 0 && <button className="btn pri" onClick={() => setDraft(newDraft())} type="button"><Plus size={16} /> Zeitplan erstellen</button>}
        </div>
      ) : (
        <div className="task-card-list">
          {visibleSchedules.map((schedule) => (
            <article className={`task-card${schedule.enabled ? "" : " is-paused"}`} key={schedule.id}>
              <div className="task-card-main">
                <div className={`task-status-icon${schedule.enabled ? " is-active" : ""}`}><CalendarDots size={20} /></div>
                <div className="task-card-copy">
                  <div className="task-title-row">
                    <h3>{schedule.name}</h3>
                    <span className={`status-badge ${schedule.enabled ? "is-active" : "is-paused"}`}>{schedule.enabled ? "Aktiv" : "Pausiert"}</span>
                  </div>
                  <p>{describeSchedule(schedule)} <span>·</span> {schedule.timezone}</p>
                  <div className="task-action-label">{schedule.actions.length} {schedule.actions.length === 1 ? "Aktion" : "Aktionen"}</div>
                  <ol className="schedule-action-list">
                    {schedule.actions.map((action) => (
                      <li key={action.id}>
                        {action.type === "backup" ? "Backup erstellen" : `Konsolenbefehl: ${action.command}`}
                      </li>
                    ))}
                  </ol>
                </div>
                <div className="task-card-actions">
                  <button className="btn task-details-button" disabled={busyId === schedule.id} onClick={() => void toggleSchedule(schedule)} type="button">
                    {schedule.enabled ? "Pausieren" : "Aktivieren"}
                  </button>
                  <button className="btn" disabled={busyId === schedule.id} onClick={() => setDraft(draftFrom(schedule))} type="button" aria-label={`Zeitplan ${schedule.name} bearbeiten`}>
                    <PencilSimple size={16} /> Bearbeiten
                  </button>
                  <button className="btn" disabled={busyId === schedule.id} onClick={() => void deleteSchedule(schedule)} type="button" aria-label={`Zeitplan ${schedule.name} löschen`}>
                    <Trash size={16} /> Löschen
                  </button>
                  <button className="btn pri" disabled={busyId === schedule.id} onClick={() => void runSchedule(schedule)} type="button">
                    <Play size={15} weight="fill" /> {busyId === schedule.id ? "Läuft …" : "Jetzt ausführen"}
                  </button>
                </div>
              </div>
              <div className="task-details-grid">
                <div><span><Clock size={15} /> Nächster Lauf</span><strong>{schedule.next ? dateLabel(schedule.next) : schedule.enabled ? "Kein Lauf geplant" : "Pausiert"}</strong></div>
                <div><span><ArrowClockwise size={15} /> Letzter Lauf</span><strong>{schedule.last ? dateLabel(schedule.last) : schedule.lastRun ? dateLabel(new Date(schedule.lastRun).toISOString()) : "Noch nicht ausgeführt"}</strong></div>
                <div><span>Cron-Ausdruck</span><code>{schedule.cron}</code></div>
              </div>
            </article>
          ))}
        </div>
      )}

      {legacyTasks.length > 0 && (
        <section className="legacy-task-section">
          <div className="workflow-section-heading">
            <div><h3>Aufgaben aus der alten Konfiguration</h3><p>Diese bestehenden Tasks werden weiterhin nach ihrer config.yml-Regel ausgeführt.</p></div>
          </div>
          <div className="task-card-list">
            {legacyTasks.map((task) => (
              <article className={`task-card${task.enabled ? "" : " is-paused"}`} key={task.id}>
                <div className="task-card-main">
                  <div className="task-status-icon"><TerminalWindow size={20} /></div>
                  <div className="task-card-copy">
                    <div className="task-title-row"><h3>{task.id}</h3><span className={`status-badge ${task.enabled ? "is-active" : "is-paused"}`}>{task.enabled ? "Aktiv" : "Pausiert"}</span></div>
                    <p>{task.cron} <span>·</span> {task.timezone}</p>
                    <div className="task-action-label">{task.action === "backup" ? "Backup erstellen" : task.action === "command" ? task.command : task.action}</div>
                  </div>
                  <div className="task-card-actions">
                    <button className="btn pri" disabled={busyId === task.id} onClick={() => void runLegacyTask(task)} type="button"><Play size={15} weight="fill" /> Jetzt ausführen</button>
                  </div>
                </div>
              </article>
            ))}
          </div>
        </section>
      )}

      {draft && (
        <div className="workflow-modal-backdrop" onMouseDown={(event) => { if (event.target === event.currentTarget) setDraft(null); }}>
          <section aria-labelledby="schedule-editor-title" aria-modal="true" className="workflow-modal" role="dialog">
            <header className="workflow-modal-header">
              <div><span className="feature-icon"><CalendarDots size={20} /></span><div><h2 id="schedule-editor-title">{schedules.some((item) => item.id === draft.id) ? "Zeitplan bearbeiten" : "Zeitplan erstellen"}</h2><p>Lege Intervall, Zeitzone und Aktionen fest.</p></div></div>
              <button className="btn" onClick={() => setDraft(null)} type="button">Schließen</button>
            </header>
            <div className="schedule-form-grid">
              <label className="schedule-form-field schedule-form-wide">Name
                <input maxLength={80} onChange={(event) => updateDraft({ name: event.target.value })} required value={draft.name} />
              </label>
              <label className="schedule-form-field">Intervall
                <select onChange={(event) => updateDraft({ interval: event.target.value as Interval })} value={draft.interval}>
                  <option value="HOURLY">Stündlich</option>
                  <option value="DAILY">Täglich</option>
                  <option value="WEEKLY">Wöchentlich</option>
                </select>
              </label>
              {draft.interval === "HOURLY" ? (
                <label className="schedule-form-field">Minute der Stunde
                  <input max={59} min={0} onChange={(event) => updateDraft({ intervalValue: Number(event.target.value) })} type="number" value={draft.intervalValue} />
                </label>
              ) : draft.interval === "DAILY" ? (
                <label className="schedule-form-field">Uhrzeit
                  <input onChange={(event) => {
                    const [hour, minute] = event.target.value.split(":").map(Number);
                    updateDraft({ intervalValue: hour, timeValue: minute });
                  }} required type="time" value={`${String(draft.intervalValue).padStart(2, "0")}:${String(draft.timeValue).padStart(2, "0")}`} />
                </label>
              ) : (
                <>
                  <label className="schedule-form-field">Wochentag
                    <select onChange={(event) => updateDraft({ intervalValue: Number(event.target.value) })} value={draft.intervalValue}>
                      {weekdays.map((day, index) => <option key={day} value={index}>{day}</option>)}
                    </select>
                  </label>
                  <label className="schedule-form-field">Uhrzeit
                    <input onChange={(event) => {
                      const [hour, minute] = event.target.value.split(":").map(Number);
                      updateDraft({ timeValue: hour * 60 + minute });
                    }} required type="time" value={`${String(Math.floor(draft.timeValue / 60)).padStart(2, "0")}:${String(draft.timeValue % 60).padStart(2, "0")}`} />
                  </label>
                </>
              )}
              <label className="schedule-form-field">Zeitzone
                <input maxLength={64} onChange={(event) => updateDraft({ timezone: event.target.value })} placeholder="Europe/Berlin" required value={draft.timezone} />
              </label>
              <label className="schedule-enabled-field"><input checked={draft.enabled} onChange={(event) => updateDraft({ enabled: event.target.checked })} type="checkbox" /> Zeitplan aktivieren</label>
            </div>

            <div className="schedule-actions-editor">
              <div className="workflow-section-heading">
                <div><h3>Aktionen</h3><p>Die Aktionen werden in dieser Reihenfolge nacheinander ausgeführt.</p></div>
                <button className="btn" disabled={draft.actions.length >= 50} onClick={() => updateDraft({ actions: [...draft.actions, { id: newId(), type: "command", command: "" }] })} type="button"><Plus size={16} /> Aktion hinzufügen</button>
              </div>
              {draft.actions.map((action, index) => (
                <div className="schedule-action-editor" key={action.id}>
                  <span className="schedule-action-order">{index + 1}</span>
                  <label className="schedule-form-field">Aktion
                    <select onChange={(event) => updateDraft({ actions: draft.actions.map((item) => item.id === action.id ? { ...item, type: event.target.value as ActionType, command: "" } : item) })} value={action.type}>
                      <option value="backup">Backup erstellen</option>
                      <option value="command">Konsolenbefehl</option>
                    </select>
                  </label>
                  {action.type === "command" && (
                    <label className="schedule-form-field schedule-action-command">Befehl
                      <input maxLength={512} onChange={(event) => updateDraft({ actions: draft.actions.map((item) => item.id === action.id ? { ...item, command: event.target.value } : item) })} placeholder="say Wartung beginnt bald" required value={action.command} />
                    </label>
                  )}
                  <button aria-label={`Aktion ${index + 1} entfernen`} className="btn" disabled={draft.actions.length <= 1} onClick={() => updateDraft({ actions: draft.actions.filter((item) => item.id !== action.id) })} type="button"><Trash size={16} /></button>
                </div>
              ))}
              <p className="schedule-backup-hint">Backup-Aktionen verwenden die globalen Backup-Einstellungen unter Backup &amp; Speicherung.</p>
            </div>
            <footer className="workflow-modal-footer">
              <button className="btn" onClick={() => setDraft(null)} type="button">Abbrechen</button>
              <button className="btn pri" disabled={busyId === draft.id || !draft.name.trim() || draft.actions.some((action) => action.type === "command" && !action.command.trim())} onClick={() => void saveDraft()} type="button">
                <FloppyDisk size={17} /> {busyId === draft.id ? "Speichert …" : "Zeitplan speichern"}
              </button>
            </footer>
          </section>
        </div>
      )}
    </div>
  );
}
