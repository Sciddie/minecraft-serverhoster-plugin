import { useCallback, useEffect, useMemo, useState } from "react";
import {
  ArrowClockwise,
  CalendarDots,
  Clock,
  FileText,
  Play,
  Sliders,
  TerminalWindow,
} from "@phosphor-icons/react";
import { dashboardRequest } from "../lib/hostApi";

interface ScheduledTask {
  id: string;
  enabled: boolean;
  cron: string;
  timezone: string;
  action: string;
  command: string;
  server: string;
  next: string;
  last: string;
}

function describeCron(cron: string): string {
  const parts = cron.trim().split(/\s+/);
  if (parts.length !== 5) return cron || "Zeitplan nicht angegeben";
  const [minute, hour, day, month, weekday] = parts;
  if (day === "*" && month === "*" && weekday === "*") {
    if (hour === "*" && minute.startsWith("*/")) return `Alle ${minute.slice(2)} Minuten`;
    if (hour === "*" && minute === "0") return "Jede Stunde";
    if (hour !== "*" && minute !== "*" && /^\d+$/.test(hour) && /^\d+$/.test(minute)) {
      return `Täglich um ${hour.padStart(2, "0")}:${minute.padStart(2, "0")}`;
    }
  }
  if (day === "*" && month === "*" && weekday !== "*" && /^\d+$/.test(hour) && /^\d+$/.test(minute)) {
    const dayName = ["Sonntag", "Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag"][Number(weekday)];
    if (dayName) return `${dayName} um ${hour.padStart(2, "0")}:${minute.padStart(2, "0")}`;
  }
  return cron;
}

function actionLabel(task: ScheduledTask): string {
  if (task.action === "backup") return "Backup erstellen";
  if (task.action === "command") return "Konsolenbefehl";
  return task.action;
}

function dateLabel(value: string): string {
  if (!value) return "Noch nicht ausgeführt";
  const time = new Date(value);
  return Number.isNaN(time.getTime()) ? value : time.toLocaleString("de-DE");
}

export default function TaskWorkflow() {
  const [tasks, setTasks] = useState<ScheduledTask[]>([]);
  const [loading, setLoading] = useState(false);
  const [runningId, setRunningId] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [filter, setFilter] = useState<"all" | "enabled" | "paused">("all");

  const refresh = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      setTasks(await dashboardRequest<ScheduledTask[]>("/api/tasks"));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Aufgaben konnten nicht geladen werden.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    const onPageChange = (event: Event) => {
      if ((event as CustomEvent<string>).detail === "tasks") void refresh();
    };
    window.addEventListener("dashboard:tab", onPageChange);
    return () => window.removeEventListener("dashboard:tab", onPageChange);
  }, [refresh]);

  const visibleTasks = useMemo(
    () =>
      tasks.filter((task) =>
        filter === "all" ? true : filter === "enabled" ? task.enabled : !task.enabled,
      ),
    [filter, tasks],
  );

  const runTask = async (task: ScheduledTask) => {
    setRunningId(task.id);
    setError("");
    try {
      await dashboardRequest(`/api/tasks/run?id=${encodeURIComponent(task.id)}`, { method: "POST" });
      window.dashboardUi?.notify(`Aufgabe „${task.id}“ wurde gestartet.`);
      await refresh();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Aufgabe konnte nicht gestartet werden.");
    } finally {
      setRunningId(null);
    }
  };

  return (
    <div className="feature-workflow">
      <section className="feature-heading-card">
        <div className="feature-heading-copy">
          <span className="feature-icon"><CalendarDots size={23} weight="fill" /></span>
          <div>
            <h2>Geplante Aufgaben</h2>
            <p>Automatisierte Aktionen aus der ServerHoster-Konfiguration.</p>
          </div>
        </div>
        <button className="btn" onClick={() => void refresh()} type="button">
          <ArrowClockwise size={17} /> Aktualisieren
        </button>
      </section>

      <aside className="workflow-notice">
        <Sliders size={19} />
        <div>
          <strong>Aufgaben werden in config.yml verwaltet</strong>
          <p>
            Zeitplan, Zeitzone und Aktion bleiben dort als Rohtext konfigurierbar.
            Die vorhandenen Task-Felder werden nicht in ein separates Formular umgewandelt.
          </p>
        </div>
        <button className="btn" onClick={() => window.dashboardUi?.navigate("config")} type="button">
          config.yml öffnen
        </button>
      </aside>

      <section className="workflow-toolbar">
        <div>
          <strong>{tasks.length} {tasks.length === 1 ? "Aufgabe" : "Aufgaben"}</strong>
          <span className="muted"> · {tasks.filter((task) => task.enabled).length} aktiv</span>
        </div>
        <div className="segmented-control" aria-label="Aufgaben filtern">
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
      {loading && tasks.length === 0 ? (
        <div className="workflow-empty">Aufgaben werden geladen …</div>
      ) : visibleTasks.length === 0 ? (
        <div className="workflow-empty">
          <CalendarDots size={32} />
          <strong>{tasks.length === 0 ? "Noch keine Aufgaben eingerichtet" : "Keine Aufgaben in diesem Filter"}</strong>
          <span>{tasks.length === 0 ? "Du kannst den täglichen Backup-Task oder eigene Aktionen in config.yml definieren." : "Wähle einen anderen Filter, um weitere Aufgaben zu sehen."}</span>
        </div>
      ) : (
        <div className="task-card-list">
          {visibleTasks.map((task) => {
            const expanded = expandedId === task.id;
            return (
              <article className={`task-card${task.enabled ? "" : " is-paused"}`} key={task.id}>
                <div className="task-card-main">
                  <div className={`task-status-icon${task.enabled ? " is-active" : ""}`}>
                    {task.action === "command" ? <TerminalWindow size={20} /> : <CalendarDots size={20} />}
                  </div>
                  <div className="task-card-copy">
                    <div className="task-title-row">
                      <h3>{task.id}</h3>
                      <span className={`status-badge ${task.enabled ? "is-active" : "is-paused"}`}>
                        {task.enabled ? "Aktiv" : "Pausiert"}
                      </span>
                    </div>
                    <p>{describeCron(task.cron)} <span>·</span> {task.timezone || "UTC"}</p>
                    <div className="task-action-label">{actionLabel(task)}</div>
                  </div>
                  <div className="task-card-actions">
                    <button
                      className="btn task-details-button"
                      aria-expanded={expanded}
                      onClick={() => setExpandedId(expanded ? null : task.id)}
                      type="button"
                    >
                      {expanded ? "Weniger" : "Details"}
                    </button>
                    <button
                      className="btn pri"
                      disabled={runningId === task.id}
                      onClick={() => void runTask(task)}
                      type="button"
                    >
                      <Play size={15} weight="fill" />
                      {runningId === task.id ? "Startet …" : "Jetzt ausführen"}
                    </button>
                  </div>
                </div>
                {expanded && (
                  <div className="task-details-grid">
                    <div><span><Clock size={15} /> Nächster Lauf</span><strong>{dateLabel(task.next)}</strong></div>
                    <div><span><ArrowClockwise size={15} /> Letzter Lauf</span><strong>{dateLabel(task.last)}</strong></div>
                    <div><span><FileText size={15} /> Aktion</span><strong>{task.action}</strong></div>
                    {task.command && <div><span><TerminalWindow size={15} /> Befehl</span><code>{task.command}</code></div>}
                    {task.server && <div><span>Serverprofil</span><strong>{task.server}</strong></div>}
                    <div><span>Cron-Ausdruck</span><code>{task.cron}</code></div>
                  </div>
                )}
              </article>
            );
          })}
        </div>
      )}
    </div>
  );
}
