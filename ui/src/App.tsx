import { useEffect, useLayoutEffect, useRef, useState } from "react";
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
import { dashboardRequest, jsonBody, type ConfirmOptions, type PromptOptions } from "./lib/hostApi";
// Window.dashboardUi and Window.tab are declared once in lib/hostApi.ts.
import { installFileEditors, type FileEditors } from "./lib/fileEditor";
import dashboardMarkup from "./dashboardPanels.html?raw";
import dashboardScript from "./dashboardScript.js?raw";

declare global {
  interface Window {
    dashboardLegacyLoaded?: boolean;
    dashboardEditors?: FileEditors;
    doLogin?: () => void;
    start?: () => void;
    logout?: () => void;
    showLogin?: () => void;
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

const routes: Record<string, string> = {
  status: "/dashboard",
  console: "/console",
  players: "/players",
  files: "/files",
  logs: "/logs",
  props: "/properties",
  config: "/config",
  settings: "/settings",
  diag: "/diagnostics",
  setup: "/setup",
  tasks: "/tasks",
  backup: "/backup",
};

function tabFromPath(path: string): string {
  return Object.entries(routes).find(([, route]) => route === path)?.[0] ?? "status";
}

function readLegacyDashboard() {
  const document = new DOMParser().parseFromString(
    `<!DOCTYPE html><html><body><main>${dashboardMarkup}</main></body></html>`,
    "text/html",
  );
  const main = document.querySelector("main");
  if (!main || !dashboardScript) {
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
  const settingsSection = main.querySelector("#t-settings");
  if (!settingsSection) throw new Error("Dashboard section t-settings is missing");
  const settingsRoot = document.createElement("div");
  settingsRoot.id = "settings-workflow-root";
  settingsSection.replaceChildren(settingsRoot);
  return { panels: main.innerHTML, script: dashboardScript };
}

const legacyDashboard = readLegacyDashboard();

export default function App() {
  const [activeTab, setActiveTab] = useState(() => tabFromPath(window.location.pathname));
  const [setupRequired, setSetupRequired] = useState(false);
  const [authReady, setAuthReady] = useState(false);
  const [passwordSetup, setPasswordSetup] = useState<boolean | null>(null);
  const [setupCode, setSetupCode] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [passwordConfirmation, setPasswordConfirmation] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [passwordError, setPasswordError] = useState("");
  const [passwordBusy, setPasswordBusy] = useState(false);
  const setupRequiredRef = useRef(false);
  const [dialog, setDialog] = useState<WorkflowDialog | null>(null);
  const [dialogValue, setDialogValue] = useState("");
  const pendingDialog = useRef<((value: boolean | string | null) => void) | null>(null);
  const activePage =
    groups.flatMap((group) => group.items).find((item) => item.id === activeTab)?.label ?? "Übersicht";

  useEffect(() => {
    dashboardRequest<{ passwordSetupRequired: boolean }>("/api/auth-state")
      .then((state) => setPasswordSetup(state.passwordSetupRequired))
      .catch((cause) => setPasswordError(cause instanceof Error ? cause.message : "Dashboard-Status konnte nicht geladen werden."));
  }, []);

  useLayoutEffect(() => {
    document.getElementById("app")?.classList.toggle("onboarding-active", setupRequired);
  }, [setupRequired]);

  useLayoutEffect(() => {
    const onUnauthorized = () => window.showLogin?.();
    const onAuthReady = () => setAuthReady(true);
    const onTabChange = (event: Event) => setActiveTab((event as CustomEvent<string>).detail);
    const onSetupRequired = (event: Event) => {
      const required = (event as CustomEvent<boolean>).detail;
      const becameRequired = required && !setupRequiredRef.current;
      setupRequiredRef.current = required;
      if (becameRequired) navigateTo("setup", "replace");
      setSetupRequired(required);
    };
    const onStarted = () => navigateTo(tabFromPath(window.location.pathname), "replace");
    const onPopState = () => {
      if (document.getElementById("app")?.classList.contains("hide")) {
        setActiveTab(tabFromPath(window.location.pathname));
      } else {
        navigateTo(tabFromPath(window.location.pathname), "replace");
      }
    };
    window.addEventListener("dashboard:unauthorized", onUnauthorized);
    window.addEventListener("dashboard:auth-ready", onAuthReady);
    window.addEventListener("dashboard:tab", onTabChange);
    window.addEventListener("dashboard:setup-required", onSetupRequired);
    window.addEventListener("dashboard:started", onStarted);
    window.addEventListener("popstate", onPopState);
    return () => {
      window.removeEventListener("dashboard:unauthorized", onUnauthorized);
      window.removeEventListener("dashboard:auth-ready", onAuthReady);
      window.removeEventListener("dashboard:tab", onTabChange);
      window.removeEventListener("dashboard:setup-required", onSetupRequired);
      window.removeEventListener("dashboard:started", onStarted);
      window.removeEventListener("popstate", onPopState);
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
    window.dashboardUi = {
      confirm: async (options) => (await showDialog({ ...options, kind: "confirm" })) === true,
      prompt: async (options) => {
        const result = await showDialog({ ...options, kind: "prompt" });
        return typeof result === "string" ? result : null;
      },
      navigate: (tab) => navigateTo(tab, "push"),
      notify: (message, error) => window.toast?.(message, error),
    };

    window.dashboardEditors = installFileEditors();
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

  function navigateTo(requestedTab: string, mode: "push" | "replace") {
    const tab = setupRequiredRef.current ? "setup" : requestedTab;
    const path = routes[tab] ?? routes.status;
    const effectiveTab = routes[tab] ? tab : "status";
    if (window.location.pathname !== path) {
      if (mode === "push") window.history.pushState(null, "", path);
      else window.history.replaceState(null, "", path);
    }
    setActiveTab(effectiveTab);
    window.tab?.(effectiveTab);
    window.dispatchEvent(new CustomEvent("dashboard:tab", { detail: effectiveTab }));
  }

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

  const generatePassword = () => {
    if (!globalThis.crypto?.getRandomValues) {
      setPasswordError("Sichere Passworterzeugung ist in diesem Browser nicht verfügbar.");
      return;
    }
    const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    const bytes = globalThis.crypto.getRandomValues(new Uint8Array(24));
    const generated = Array.from(bytes, (value) => alphabet[value & 63]).join("");
    setNewPassword(generated);
    setPasswordConfirmation(generated);
    setShowPassword(true);
    setPasswordError("");
  };

  const savePassword = async () => {
    if (newPassword !== passwordConfirmation) {
      setPasswordError("Die Passwörter stimmen nicht überein.");
      return;
    }
    setPasswordBusy(true);
    setPasswordError("");
    try {
      await dashboardRequest("/api/password-setup", {
        method: "POST",
        body: jsonBody({ code: setupCode.trim(), password: newPassword }),
      });
      setPasswordSetup(false);
      setSetupCode("");
      setNewPassword("");
      setPasswordConfirmation("");
      window.start?.();
    } catch (cause) {
      setPasswordError(cause instanceof Error ? cause.message : "Passwort konnte nicht gespeichert werden.");
    } finally {
      setPasswordBusy(false);
    }
  };

  return (
    <>
      <section id="login" className="login-screen">
        <form
          className="login-card"
          onSubmit={(event) => {
            event.preventDefault();
            if (passwordSetup) void savePassword();
            else window.doLogin?.();
          }}
        >
          <div className="brand-mark login-mark">SH</div>
          <p className="eyebrow">SERVERHOSTER</p>
          <h1>{passwordSetup ? "Dashboard einrichten" : "Anmelden"}</h1>
          {passwordSetup ? <>
            <p className="login-description">Erster Einrichtungsschritt: Gib den einmaligen Code aus dem Server-Log ein und lege dein Dashboard-Passwort fest. Der Code ändert sich bei einem Host-Neustart.</p>
            {location.protocol !== "https:" && <p className="login-description">Achtung: Über HTTP werden Code und Passwort unverschlüsselt übertragen. Verwende ein vertrauenswürdiges Netzwerk oder HTTPS.</p>}
            <label htmlFor="setup-code">Einrichtungscode aus dem Server-Log</label>
            <input id="setup-code" autoComplete="off" value={setupCode} onChange={(event) => setSetupCode(event.target.value)} />
            <label htmlFor="new-password">Neues Passwort (mindestens 12 Zeichen)</label>
            <input id="new-password" type={showPassword ? "text" : "password"} autoComplete="new-password" value={newPassword} onChange={(event) => setNewPassword(event.target.value)} />
            <button className="btn" type="button" onClick={generatePassword}>Sicheres Passwort erzeugen</button>
            {showPassword && <p className="login-hint">Das erzeugte Passwort ist sichtbar. Bitte sicher speichern, bevor du fortfährst.</p>}
            <label htmlFor="confirm-password">Passwort wiederholen</label>
            <input id="confirm-password" type="password" autoComplete="new-password" value={passwordConfirmation} onChange={(event) => setPasswordConfirmation(event.target.value)} />
            <button className="btn pri login-button" type="submit" disabled={!authReady || passwordBusy}>
              {passwordBusy ? "Speichere …" : "Passwort setzen und fortfahren"}
            </button>
          </> : <>
          <p className="login-description">Melde dich an, um deinen Server zu verwalten.</p>
          <label htmlFor="pw">Dashboard-Passwort</label>
          <input
            id="pw"
            type="password"
            placeholder="Passwort"
            autoComplete="current-password"
            autoFocus
          />
          <button className="btn pri login-button" type="submit" disabled={!authReady || passwordSetup === null}>
            Sicher anmelden
          </button>
          <p id="lerr" className="login-error" role="alert" />
          </>}
          {passwordError && <p className="login-error" role="alert">{passwordError}</p>}
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
                    onClick={() => navigateTo(item.id, "push")}
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
            <span className="host-indicator"><i /> <span id="host-connection">Verbindung wird geprüft</span></span>
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
