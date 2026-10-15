import { useLayoutEffect, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import type { FormEvent } from "react";
import {
  ArchiveIcon,
  CalendarDots,
  FileText,
  FolderOpen,
  GearSix,
  House,
  Pulse,
  Scroll,
  Sliders,
  ShieldWarning,
  TerminalWindow,
  UsersThree,
  X,
} from "@phosphor-icons/react";
import BackupWorkflow from "./components/BackupWorkflow";
import { PlayerWorkflow } from "./components/PlayerWorkflow";
import SetupWorkflow from "./components/SetupWorkflow";
import SettingsWorkflow from "./components/SettingsWorkflow";
import TaskWorkflow from "./components/TaskWorkflow";
import type { ConfirmOptions, PromptOptions } from "./lib/hostApi";
import legacyDashboardHtml from "../../src/main/resources/web/index.html?raw";

declare global {
  interface Window {
    dashboardLegacyLoaded?: boolean;
    dashboardUi?: {
      confirm: (options: ConfirmOptions) => Promise<boolean>;
      prompt: (options: PromptOptions) => Promise<string | null>;
      navigate: (tab: string) => void;
      notify: (message: string, error?: boolean) => void;
    };
    doLogin?: () => void;
    logout?: () => void;
    tab?: (tab: string) => void;
    toast?: (message: string, error?: boolean) => void;
  }
}

type WorkflowDialog =
  | (ConfirmOptions & { kind: "confirm" })
  | (PromptOptions & { kind: "prompt" });

const groups = [
  {
    label: "ÜBERBLICK",
    items: [
      { id: "status", label: "Übersicht", icon: <House size={18} weight="regular" /> },
      { id: "console", label: "Konsole", icon: <TerminalWindow size={18} weight="regular" /> },
      { id: "players", label: "Spieler", icon: <UsersThree size={18} weight="regular" /> },
    ],
  },
  {
    label: "VERWALTUNG",
    items: [
      { id: "files", label: "Dateien", icon: <FolderOpen size={18} weight="regular" /> },
      { id: "logs", label: "Logs", icon: <Scroll size={18} weight="regular" /> },
      { id: "props", label: "server.properties", icon: <Sliders size={18} weight="regular" /> },
      { id: "config", label: "config.yml", icon: <FileText size={18} weight="regular" /> },
      { id: "settings", label: "Einstellungen", icon: <GearSix size={18} weight="regular" /> },
    ],
  },
  {
    label: "SERVER",
    items: [
      { id: "diag", label: "Diagnose", icon: <Pulse size={18} weight="regular" /> },
      { id: "setup", label: "Einrichtung", icon: <Sliders size={18} weight="regular" /> },
      { id: "tasks", label: "Aufgaben", icon: <CalendarDots size={18} weight="regular" /> },
      { id: "backup", label: "Backup", icon: <ArchiveIcon size={18} weight="regular" /> },
    ],
  },
];

function readLegacyDashboard() {
  const document = new DOMParser().parseFromString(legacyDashboardHtml, "text/html");
  const main = document.querySelector("main");
  const script = document.querySelector("script");
  if (!main || !script?.textContent) {
    throw new Error("The bundled ServerHoster dashboard source is incomplete");
  }
  for (const [sectionId, rootId] of [
    ["t-players", "player-workflow-root"],
    ["t-setup", "setup-workflow-root"],
    ["t-tasks", "task-workflow-root"],
    ["t-backup", "backup-workflow-root"],
  ]) {
    const section = main.querySelector(`#${sectionId}`);
    if (!section) throw new Error(`Dashboard section ${sectionId} is missing`);
    const root = document.createElement("div");
    root.id = rootId;
    section.replaceChildren(root);
  }
  const settingsSection = document.createElement("section");
  settingsSection.id = "t-settings";
  settingsSection.className = "hide";
  const settingsRoot = document.createElement("div");
  settingsRoot.id = "settings-workflow-root";
  settingsSection.append(settingsRoot);
  main.append(settingsSection);
  const safeScript = script.textContent.replace(
    /if\s*\(\s*t\s*===\s*['"](?:players|setup|backup|tasks)['"]\s*\)\s*load(?:Players|Setup|Backup|Tasks)\s*\(\s*\)\s*;?/g,
    "",
  );
  return { panels: main.innerHTML, script: safeScript };
}

const legacyDashboard = readLegacyDashboard();

export default function App() {
  const [activeTab, setActiveTab] = useState("status");
  const [dialog, setDialog] = useState<WorkflowDialog | null>(null);
  const [dialogValue, setDialogValue] = useState("");
  const pendingDialog = useRef<((value: boolean | string | null) => void) | null>(null);
  const activePage =
    groups.flatMap((group) => group.items).find((item) => item.id === activeTab)?.label ?? "Übersicht";

  useLayoutEffect(() => {
    const onUnauthorized = () => window.logout?.();
    const onTabChange = (event: Event) => setActiveTab((event as CustomEvent<string>).detail);
    window.addEventListener("dashboard:unauthorized", onUnauthorized);
    window.addEventListener("dashboard:tab", onTabChange);
    return () => {
      window.removeEventListener("dashboard:unauthorized", onUnauthorized);
      window.removeEventListener("dashboard:tab", onTabChange);
    };
  }, []);

  useLayoutEffect(() => {
    if (window.dashboardLegacyLoaded) return;
    const showDialog = (request: WorkflowDialog) =>
      new Promise<boolean | string | null>((resolve) => {
        pendingDialog.current?.(null);
        pendingDialog.current = resolve;
        setDialogValue(request.kind === "prompt" ? request.initialValue ?? "" : "");
        setDialog(request);
      });
    const navigate = (tab: string) => {
      setActiveTab(tab);
      window.tab?.(tab);
      window.dispatchEvent(new CustomEvent("dashboard:tab", { detail: tab }));
    };
    window.dashboardUi = {
      confirm: async (options) => (await showDialog({ ...options, kind: "confirm" })) === true,
      prompt: async (options) => {
        const result = await showDialog({ ...options, kind: "prompt" });
        return typeof result === "string" ? result : null;
      },
      navigate,
      notify: (message, error) => window.toast?.(message, error),
    };

    const script = document.createElement("script");
    script.textContent = legacyDashboard.script;
    document.body.appendChild(script);
    const playerRoot = document.getElementById("player-workflow-root");
    const setupRoot = document.getElementById("setup-workflow-root");
    const taskRoot = document.getElementById("task-workflow-root");
    const backupRoot = document.getElementById("backup-workflow-root");
    const settingsRoot = document.getElementById("settings-workflow-root");
    if (!playerRoot || !setupRoot || !taskRoot || !backupRoot || !settingsRoot) {
      throw new Error("Dashboard workflow mount point is missing");
    }
    createRoot(playerRoot).render(<PlayerWorkflow />);
    createRoot(setupRoot).render(<SetupWorkflow />);
    createRoot(taskRoot).render(<TaskWorkflow />);
    createRoot(backupRoot).render(<BackupWorkflow />);
    createRoot(settingsRoot).render(<SettingsWorkflow />);
    window.dashboardLegacyLoaded = true;
  }, []);

  const navigate = (tab: string) => {
    setActiveTab(tab);
    window.tab?.(tab);
    window.dispatchEvent(new CustomEvent("dashboard:tab", { detail: tab }));
  };

  const closeDialog = (result: boolean | string | null) => {
    pendingDialog.current?.(result);
    pendingDialog.current = null;
    setDialog(null);
  };

  const submitDialog = (event: FormEvent) => {
    event.preventDefault();
    if (!dialog) return;
    if (dialog.kind === "prompt") {
      const prompt = dialog as PromptOptions;
      if (prompt.requiredValue && dialogValue !== prompt.requiredValue) return;
      closeDialog(dialogValue);
      return;
    }
    closeDialog(true);
  };

  return (
    <>
      <section id="login" className="login-screen">
        <form
          className="login-card vd-rise"
          onSubmit={(event) => {
            event.preventDefault();
            window.doLogin?.();
          }}
        >
          <div className="brand-mark login-mark">SH</div>
          <p className="eyebrow">SERVERHOSTER</p>
          <h1>Anmelden</h1>
          <p className="login-description">Melde dich an, um deinen Server zu verwalten.</p>
          <label htmlFor="pw">Dashboard-Passwort</label>
          <input
            id="pw"
            type="password"
            placeholder="Passwort"
            autoComplete="current-password"
            autoFocus
          />
          <button className="btn pri login-button" type="submit">
            Sicher anmelden
          </button>
          <p id="lerr" className="login-error" role="alert" />
          <p className="login-hint">
            Passwort: <code>plugins/ServerhosterPlugin/dashboard-password.txt</code>
          </p>
        </form>
      </section>

      <div id="app" className="app-shell hide vd-aurora">
        <aside className="sidebar">
          <div className="sidebar-brand">
            <div className="brand-mark">SH</div>
            <div className="brand-copy">
              <span className="brand-name">ServerHoster</span>
              <span className="brand-caption">Server control panel</span>
            </div>
          </div>

          <div className="server-picker">
            <span className="eyebrow">CHILD-SERVER</span>
            <span id="hstate" className="server-state muted">
              ● Status wird geladen
            </span>
          </div>

          <nav className="side-navigation" aria-label="Dashboard">
            {groups.map((group) => (
              <section className="nav-group" key={group.label}>
                <h2>{group.label}</h2>
                {group.items.map((item) => (
                  <button
                    aria-current={activeTab === item.id ? "page" : undefined}
                    className={`nav-item${activeTab === item.id ? " selected" : ""}`}
                    key={item.id}
                    onClick={() => navigate(item.id)}
                    type="button"
                  >
                    {item.icon}
                    <span>{item.label}</span>
                  </button>
                ))}
              </section>
            ))}
          </nav>

          <div className="sidebar-footer">
            <span className="host-indicator"><i /> Spigot-Host verbunden</span>
            <button className="logout-button" onClick={() => window.logout?.()} type="button">
              Abmelden
            </button>
          </div>
        </aside>

        <div className="dashboard-main">
          <header className="topbar">
            <div>
              <span className="eyebrow">SERVERHOSTER / {activePage.toUpperCase()}</span>
              <h1>{activePage}</h1>
            </div>
            <span className="live-indicator"><i /> Live-Übersicht</span>
          </header>
          <main id="legacy-panels" dangerouslySetInnerHTML={{ __html: legacyDashboard.panels }} />
        </div>

        <nav id="nav" className="legacy-nav" aria-hidden="true" />
        <div id="toast" role="status" />
      </div>
      {dialog && (
        <div
          className="workflow-dialog-overlay"
          onKeyDown={(event) => {
            if (event.key === "Escape") closeDialog(dialog.kind === "confirm" ? false : null);
          }}
          onMouseDown={(event) => {
            if (event.target === event.currentTarget) closeDialog(dialog.kind === "confirm" ? false : null);
          }}
        >
          <form
            aria-labelledby="workflow-dialog-title"
            aria-modal="true"
            className="workflow-dialog vd-rise"
            onSubmit={submitDialog}
            role="dialog"
          >
            <button
              aria-label="Dialog schließen"
              className="workflow-dialog-close"
              onClick={() => closeDialog(dialog.kind === "confirm" ? false : null)}
              type="button"
            >
              <X size={18} />
            </button>
            <span className={`feature-icon${dialog.destructive ? " is-danger" : ""}`}>
              {dialog.kind === "prompt" ? <Sliders size={21} /> : <ShieldWarning size={21} />}
            </span>
            <h2 id="workflow-dialog-title">{dialog.title}</h2>
            <p>{dialog.description}</p>
            {dialog.kind === "prompt" && (
              <input
                autoFocus
                onChange={(event) => setDialogValue(event.target.value)}
                placeholder={(dialog as PromptOptions).placeholder}
                value={dialogValue}
              />
            )}
            {(dialog as PromptOptions).requiredValue && (
              <span className="dialog-match-hint">
                Bestätigung: <code>{(dialog as PromptOptions).requiredValue}</code>
              </span>
            )}
            <div className="workflow-dialog-actions">
              <button
                className="btn"
                onClick={() => closeDialog(dialog.kind === "confirm" ? false : null)}
                type="button"
              >
                Abbrechen
              </button>
              <button
                className={`btn pri${dialog.destructive ? " danger-button" : ""}`}
                disabled={
                  dialog.kind === "prompt" &&
                  !!(dialog as PromptOptions).requiredValue &&
                  dialogValue !== (dialog as PromptOptions).requiredValue
                }
                type="submit"
              >
                {dialog.confirmLabel ?? "Bestätigen"}
              </button>
            </div>
          </form>
        </div>
      )}
    </>
  );
}
