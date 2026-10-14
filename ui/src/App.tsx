import { useLayoutEffect, useState } from "react";
import {
  ArchiveIcon,
  CalendarDots,
  Cube,
  FileText,
  FolderOpen,
  House,
  Pulse,
  PuzzlePiece,
  Scroll,
  HardDrives,
  Sliders,
  TerminalWindow,
  UsersThree,
} from "@phosphor-icons/react";
import legacyDashboardHtml from "../../src/main/resources/web/index.html?raw";

declare global {
  interface Window {
    dashboardLegacyLoaded?: boolean;
    doLogin?: () => void;
    logout?: () => void;
    switchServer?: (id: string) => void;
    tab?: (tab: string) => void;
  }
}

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
    ],
  },
  {
    label: "SERVER",
    items: [
      { id: "mods", label: "Mods", icon: <PuzzlePiece size={18} weight="regular" /> },
      { id: "diag", label: "Diagnose", icon: <Pulse size={18} weight="regular" /> },
      { id: "servers", label: "Server", icon: <HardDrives size={18} weight="regular" /> },
      { id: "tasks", label: "Aufgaben", icon: <CalendarDots size={18} weight="regular" /> },
      { id: "backup", label: "Backup", icon: <ArchiveIcon size={18} weight="regular" /> },
      { id: "versions", label: "Versionen", icon: <Cube size={18} weight="regular" /> },
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
  return { panels: main.innerHTML, script: script.textContent };
}

const legacyDashboard = readLegacyDashboard();

export default function App() {
  const [activeTab, setActiveTab] = useState("status");
  const activePage =
    groups.flatMap((group) => group.items).find((item) => item.id === activeTab)?.label ?? "Übersicht";

  useLayoutEffect(() => {
    if (window.dashboardLegacyLoaded) return;
    const script = document.createElement("script");
    script.textContent = legacyDashboard.script;
    document.body.appendChild(script);
    window.dashboardLegacyLoaded = true;
  }, []);

  const navigate = (tab: string) => {
    setActiveTab(tab);
    window.tab?.(tab);
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
            Passwort: <code>plugins/FabricLauncher/dashboard-password.txt</code>
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
            <span className="eyebrow">AKTIVER SERVER</span>
            <select
              id="srv"
              aria-label="Server auswählen"
              onChange={(event) => window.switchServer?.(event.currentTarget.value)}
            />
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
    </>
  );
}
