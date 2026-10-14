import { useCallback, useEffect, useMemo, useState } from "react";
import type { FormEvent } from "react";
import {
  ArrowClockwise,
  ArrowCounterClockwise,
  CheckCircle,
  Circle,
  DownloadSimple,
  MagnifyingGlass,
  Pause,
  Play,
  PuzzlePiece,
  HardDrives,
  Sliders,
  UsersThree,
  WarningCircle,
} from "@phosphor-icons/react";
import { dashboardRequest, jsonBody } from "../lib/hostApi";

interface PlayerLists {
  whitelist: string[];
  ops: string[];
  "banned-players": string[];
  online: string[];
}

interface ServerProfile {
  id: string;
  type: string;
  mcVersion: string;
  port: number;
  portClash: boolean;
  workingDir: string;
  running: boolean;
  focus: boolean;
}

interface ModEntry {
  path: string;
  name: string;
  folder: string;
  enabled: boolean;
  toggleable: boolean;
  dir: boolean;
  size: number;
}

interface ModSearchResult {
  slug: string;
  title: string;
  downloads: number;
}

function useRefreshOnTab(tab: string, refresh: () => void) {
  useEffect(() => {
    const onPageChange = (event: Event) => {
      if ((event as CustomEvent<string>).detail === tab) refresh();
    };
    const onServerChange = () => refresh();
    window.addEventListener("dashboard:tab", onPageChange);
    window.addEventListener("dashboard:server-changed", onServerChange);
    return () => {
      window.removeEventListener("dashboard:tab", onPageChange);
      window.removeEventListener("dashboard:server-changed", onServerChange);
    };
  }, [refresh, tab]);
}

async function sendCommand(command: string): Promise<void> {
  const result = await dashboardRequest<{ ok: boolean }>("/api/cmd", {
    method: "POST",
    body: jsonBody({ command }),
  });
  if (!result.ok) throw new Error("Der Child-Server läuft nicht oder hat den Befehl abgelehnt.");
}

export function PlayerWorkflow() {
  const [lists, setLists] = useState<PlayerLists>({
    whitelist: [],
    ops: [],
    "banned-players": [],
    online: [],
  });
  const [activeList, setActiveList] = useState<keyof PlayerLists>("online");
  const [playerName, setPlayerName] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const refresh = useCallback(async () => {
    setError("");
    try {
      setLists(await dashboardRequest<PlayerLists>("/api/players"));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Spielerliste konnte nicht geladen werden.");
    }
  }, []);
  useRefreshOnTab("players", refresh);

  const runPlayerAction = async (action: string, dangerous = false) => {
    const name = playerName.trim();
    if (!/^\w{1,16}$/.test(name)) {
      setError("Bitte einen gültigen Spielernamen eingeben (1–16 Buchstaben, Zahlen oder Unterstriche).");
      return;
    }
    if (dangerous) {
      const accepted = await window.dashboardUi?.confirm({
        title: action === "ban" ? "Spieler bannen?" : "Spieler vom Server kicken?",
        description: `${name} wird per Konsolenbefehl ${action === "ban" ? "gebannt" : "vom Server getrennt"}.`,
        confirmLabel: action === "ban" ? "Spieler bannen" : "Spieler kicken",
        destructive: true,
      });
      if (!accepted) return;
    }

    setBusy(true);
    setError("");
    try {
      await sendCommand(`${action} ${name}`);
      window.dashboardUi?.notify(`Befehl ausgeführt: ${action} ${name}`);
      window.setTimeout(() => void refresh(), 1200);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Der Spielerbefehl ist fehlgeschlagen.");
    } finally {
      setBusy(false);
    }
  };

  const listConfig: { id: keyof PlayerLists; title: string }[] = [
    { id: "online", title: "Online" },
    { id: "whitelist", title: "Whitelist" },
    { id: "ops", title: "Operatoren" },
    { id: "banned-players", title: "Gebannt" },
  ];
  const visiblePlayers = lists[activeList] ?? [];

  return (
    <div className="feature-workflow">
      <section className="feature-heading-card">
        <div className="feature-heading-copy">
          <span className="feature-icon"><UsersThree size={23} weight="fill" /></span>
          <div><h2>Spielerverwaltung</h2><p>Online-Status, Listen und sichere Schnellaktionen.</p></div>
        </div>
        <button className="btn" onClick={() => void refresh()} type="button"><ArrowClockwise size={17} /> Aktualisieren</button>
      </section>

      <section className="player-actions-card">
        <div className="workflow-card-title"><div><h3>Spieleraktion</h3><p>Befehle laufen über die Child-Konsole.</p></div></div>
        <div className="player-action-form">
          <div className="player-name-field">
            <label htmlFor="managed-player">Spielername</label>
            <input
              autoComplete="off"
              id="managed-player"
              onChange={(event) => setPlayerName(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === "Enter") {
                  event.preventDefault();
                  void runPlayerAction("whitelist add");
                }
              }}
              placeholder="Spielername eingeben oder aus einer Liste wählen"
              value={playerName}
            />
          </div>
          <div className="player-action-buttons">
            <button className="btn" disabled={busy} onClick={() => void runPlayerAction("whitelist add")} type="button">Whitelist +</button>
            <button className="btn" disabled={busy} onClick={() => void runPlayerAction("whitelist remove")} type="button">Whitelist −</button>
            <button className="btn" disabled={busy} onClick={() => void runPlayerAction("op")} type="button">OP +</button>
            <button className="btn" disabled={busy} onClick={() => void runPlayerAction("deop")} type="button">OP −</button>
            <button className="btn" disabled={busy} onClick={() => void runPlayerAction("pardon")} type="button">Entbannen</button>
            <button className="btn danger-outline" disabled={busy} onClick={() => void runPlayerAction("kick", true)} type="button">Kicken</button>
            <button className="btn danger-outline" disabled={busy} onClick={() => void runPlayerAction("ban", true)} type="button">Bannen</button>
          </div>
        </div>
        <div className="whitelist-controls">
          <span>Whitelist</span>
          <button className="btn" disabled={busy} onClick={async () => { try { await sendCommand("whitelist on"); window.dashboardUi?.notify("Whitelist aktiviert"); } catch (cause) { setError(cause instanceof Error ? cause.message : "Aktion fehlgeschlagen"); } }} type="button">Aktivieren</button>
          <button className="btn" disabled={busy} onClick={async () => { try { await sendCommand("whitelist off"); window.dashboardUi?.notify("Whitelist deaktiviert"); } catch (cause) { setError(cause instanceof Error ? cause.message : "Aktion fehlgeschlagen"); } }} type="button">Deaktivieren</button>
          <button className="btn" disabled={busy} onClick={async () => { try { await sendCommand("whitelist reload"); window.dashboardUi?.notify("Whitelist neu geladen"); await refresh(); } catch (cause) { setError(cause instanceof Error ? cause.message : "Aktion fehlgeschlagen"); } }} type="button">Neu laden</button>
        </div>
        {error && <p className="workflow-error" role="alert">{error}</p>}
      </section>

      <section className="player-list-panel">
        <div className="segmented-control player-list-tabs" aria-label="Spielerliste">
          {listConfig.map((entry) => (
            <button
              aria-pressed={activeList === entry.id}
              className={activeList === entry.id ? "selected" : ""}
              key={entry.id}
              onClick={() => setActiveList(entry.id)}
              type="button"
            >
              {entry.title}<span>{lists[entry.id]?.length ?? 0}</span>
            </button>
          ))}
        </div>
        {visiblePlayers.length === 0 ? (
          <div className="workflow-empty compact-empty"><Circle size={24} /><strong>Keine Spieler in dieser Liste</strong></div>
        ) : (
          <div className="player-list">
            {visiblePlayers.map((name) => (
              <button
                className="player-list-item"
                key={name}
                onClick={() => setPlayerName(name)}
                title={`Spieler ${name} auswählen`}
                type="button"
              >
                <span className={`player-avatar${activeList === "online" ? " online" : ""}`}>{name.slice(0, 1).toUpperCase()}</span>
                <span>{name}</span>
                {activeList === "online" && <span className="online-dot" />}
              </button>
            ))}
          </div>
        )}
      </section>
    </div>
  );
}

export function ServerWorkflow() {
  const [servers, setServers] = useState<ServerProfile[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  const refresh = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      setServers(await dashboardRequest<ServerProfile[]>("/api/servers"));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Serverprofile konnten nicht geladen werden.");
    } finally {
      setLoading(false);
    }
  }, []);
  useRefreshOnTab("servers", refresh);

  const performAction = async (server: ServerProfile, action: "start" | "stop" | "restart" | "focus") => {
    if (action === "stop" || action === "restart") {
      const accepted = await window.dashboardUi?.confirm({
        title: action === "stop" ? `${server.id} stoppen?` : `${server.id} neu starten?`,
        description: action === "stop"
          ? "Der ausgewählte Child-Server wird regulär beendet. Der Spigot-Host bleibt aktiv."
          : "Der ausgewählte Child-Server wird regulär beendet und anschließend neu gestartet.",
        confirmLabel: action === "stop" ? "Child stoppen" : "Neu starten",
        destructive: action === "stop",
      });
      if (!accepted) return;
    }

    setError("");
    try {
      if (action === "focus") {
        await dashboardRequest("/api/focus", { method: "POST", body: jsonBody({ id: server.id }) });
        window.dashboardUi?.notify(`Aktives Profil: ${server.id}`);
      } else {
        await dashboardRequest(`/api/action?do=${action}&server=${encodeURIComponent(server.id)}`, { method: "POST" });
        window.dashboardUi?.notify(`${action === "start" ? "Start" : action === "stop" ? "Stopp" : "Neustart"} für ${server.id} angefordert.`);
      }
      await refresh();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Serveraktion fehlgeschlagen.");
    }
  };

  return (
    <div className="feature-workflow">
      <section className="feature-heading-card">
        <div className="feature-heading-copy">
          <span className="feature-icon"><HardDrives size={23} weight="fill" /></span>
          <div><h2>Serverprofile</h2><p>Child-Profile auf diesem Spigot-Host verwalten.</p></div>
        </div>
        <button className="btn" disabled={loading} onClick={() => void refresh()} type="button"><ArrowClockwise size={17} /> Aktualisieren</button>
      </section>
      <aside className="workflow-notice">
        <Sliders size={19} />
        <div><strong>Profile werden über config.yml verwaltet</strong><p>Das Standardverhalten bleibt ein Child gleichzeitig. Änderungen der Profile erfolgen im vorhandenen Rohtext-Editor.</p></div>
        <button className="btn" onClick={() => window.dashboardUi?.navigate("config")} type="button">config.yml öffnen</button>
      </aside>
      {error && <p className="workflow-error" role="alert">{error}</p>}
      {servers.length === 0 ? (
        <div className="workflow-empty"><HardDrives size={30} /><strong>{loading ? "Profile werden geladen …" : "Keine Serverprofile gefunden"}</strong><span>Prüfe die servers-Konfiguration in config.yml.</span></div>
      ) : (
        <div className="server-card-list">
          {servers.map((server) => (
            <article className={`server-profile-card${server.focus ? " is-focused" : ""}`} key={server.id}>
              <span className={`server-profile-icon${server.running ? " is-running" : ""}`}><HardDrives size={22} /></span>
              <div className="server-profile-info">
                <div className="task-title-row">
                  <h3>{server.id}</h3>
                  {server.focus && <span className="status-badge is-active">Aktives Profil</span>}
                  <span className={`status-badge ${server.running ? "is-active" : "is-paused"}`}>{server.running ? "Läuft" : "Gestoppt"}</span>
                  {server.portClash && <span className="status-badge is-paused"><WarningCircle size={12} /> Port-Konflikt</span>}
                </div>
                <p>{server.type} · Minecraft {server.mcVersion} · Port {server.port}</p>
                <code>{server.workingDir}</code>
              </div>
              <div className="server-profile-actions">
                {!server.focus && <button className="btn" onClick={() => void performAction(server, "focus")} type="button"><CheckCircle size={16} /> Aktivieren</button>}
                {server.running ? (
                  <>
                    <button className="btn" onClick={() => void performAction(server, "restart")} type="button"><ArrowCounterClockwise size={16} /> Neustart</button>
                    <button className="btn danger-outline" onClick={() => void performAction(server, "stop")} type="button"><Pause size={16} /> Stoppen</button>
                  </>
                ) : (
                  <button className="btn pri" onClick={() => void performAction(server, "start")} type="button"><Play size={16} weight="fill" /> Starten</button>
                )}
              </div>
            </article>
          ))}
        </div>
      )}
    </div>
  );
}

export function ModWorkflow() {
  const [mods, setMods] = useState<ModEntry[]>([]);
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<ModSearchResult[]>([]);
  const [slug, setSlug] = useState("");
  const [version, setVersion] = useState("");
  const [searching, setSearching] = useState(false);
  const [working, setWorking] = useState("");
  const [error, setError] = useState("");

  const refresh = useCallback(async () => {
    setError("");
    try {
      setMods(await dashboardRequest<ModEntry[]>("/api/mods"));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Modliste konnte nicht geladen werden.");
    }
  }, []);
  useRefreshOnTab("mods", refresh);

  const search = async (event: FormEvent) => {
    event.preventDefault();
    setSearching(true);
    setError("");
    try {
      const matches = await dashboardRequest<ModSearchResult[]>(`/api/mods/search?q=${encodeURIComponent(query)}`);
      setResults(matches);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Modrinth-Suche fehlgeschlagen.");
    } finally {
      setSearching(false);
    }
  };

  const install = async () => {
    const selectedSlug = slug.trim();
    if (!selectedSlug) return;
    const accepted = await window.dashboardUi?.confirm({
      title: `${selectedSlug} installieren?`,
      description: "Die Mod-JAR wird im ausgewählten Child-Profil abgelegt. Prüfe die Spielversion und Kompatibilität; die Änderung wird erst nach einem Child-Neustart aktiv.",
      confirmLabel: "Mod installieren",
    });
    if (!accepted) return;

    setWorking("install");
    setError("");
    try {
      const result = await dashboardRequest<{ path: string; version?: string }>("/api/mods/install", {
        method: "POST",
        body: jsonBody({ slug: selectedSlug, version: version.trim() }),
      });
      window.dashboardUi?.notify(`${result.path}${result.version ? ` · ${result.version}` : ""} — Child-Neustart erforderlich.`);
      await refresh();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Installation fehlgeschlagen.");
    } finally {
      setWorking("");
    }
  };

  const toggle = async (mod: ModEntry) => {
    const accepted = await window.dashboardUi?.confirm({
      title: `${mod.name || mod.path} ${mod.enabled ? "deaktivieren" : "aktivieren"}?`,
      description: "Die Mod-Datei wird zwischen .jar und .jar.disabled umbenannt. Die Änderung wird erst nach einem Child-Neustart aktiv.",
      confirmLabel: "Änderung vormerken",
    });
    if (!accepted) return;
    setWorking(mod.path);
    setError("");
    try {
      await dashboardRequest("/api/mods/toggle", {
        method: "POST",
        body: jsonBody({ path: mod.path, confirm: mod.name }),
      });
      window.dashboardUi?.notify(`${mod.name || mod.path} — wirksam nach Child-Neustart.`);
      await refresh();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Mod-Status konnte nicht geändert werden.");
    } finally {
      setWorking("");
    }
  };

  const enabledCount = useMemo(() => mods.filter((mod) => mod.enabled && !mod.dir).length, [mods]);

  return (
    <div className="feature-workflow">
      <section className="feature-heading-card">
        <div className="feature-heading-copy">
          <span className="feature-icon"><PuzzlePiece size={23} weight="fill" /></span>
          <div><h2>Mods &amp; Plugins</h2><p>Mod- und Plugin-Dateien für den ausgewählten Child verwalten.</p></div>
        </div>
        <button className="btn" onClick={() => void refresh()} type="button"><ArrowClockwise size={17} /> Aktualisieren</button>
      </section>
      <aside className="workflow-notice">
        <WarningCircle size={19} />
        <div><strong>Änderungen benötigen einen Child-Neustart</strong><p>Der Hoster und Spigot bleiben unberührt. Installiere ausschließlich für Profiltyp und Minecraft-Version passende Dateien; die Vanilla-Spielregeln und Lithium-/Ferrite-Defaults bleiben bestehen.</p></div>
      </aside>
      <section className="mod-search-panel">
        <div className="workflow-card-title"><div><h3>Modrinth-Katalog</h3><p>Suche nach einer Mod oder einem Plugin und wähle es zur Installation aus.</p></div></div>
        <form className="mod-search-row" onSubmit={(event) => void search(event)}>
          <label className="sr-only" htmlFor="mod-search">Modrinth durchsuchen</label>
          <MagnifyingGlass size={18} />
          <input id="mod-search" onChange={(event) => setQuery(event.target.value)} placeholder="Name oder Modrinth-Slug" value={query} />
          <button className="btn" disabled={searching || !query.trim()} type="submit">{searching ? "Sucht …" : "Suchen"}</button>
        </form>
        {results.length > 0 && (
          <div className="mod-search-results">
            {results.map((result) => (
              <button className={slug === result.slug ? "selected" : ""} key={result.slug} onClick={() => setSlug(result.slug)} type="button">
                <span><PuzzlePiece size={16} /> {result.title || result.slug}<small>{result.slug}</small></span>
                <small>{Number(result.downloads).toLocaleString("de-DE")} Downloads</small>
              </button>
            ))}
          </div>
        )}
        <div className="mod-install-row">
          <input aria-label="Modrinth-Slug" onChange={(event) => setSlug(event.target.value)} placeholder="Modrinth-Slug" value={slug} />
          <input aria-label="Optionale Mod-Version" onChange={(event) => setVersion(event.target.value)} placeholder="Version (optional)" value={version} />
          <button className="btn pri" disabled={!slug.trim() || working !== ""} onClick={() => void install()} type="button">
            <DownloadSimple size={17} /> {working === "install" ? "Installiert …" : "Installieren"}
          </button>
        </div>
      </section>
      {error && <p className="workflow-error" role="alert">{error}</p>}
      <section className="workflow-toolbar">
        <div><strong>Installierte Dateien</strong><span className="muted"> · {enabledCount} aktiv</span></div>
        <span className="muted">Toggle gilt nach Neustart</span>
      </section>
      {mods.length === 0 ? (
        <div className="workflow-empty"><PuzzlePiece size={30} /><strong>Keine Mod- oder Plugin-Dateien gefunden</strong><span>Dateien aus den Ordnern mods/ und plugins/ erscheinen hier.</span></div>
      ) : (
        <div className="mod-card-list">
          {mods.map((mod) => (
            <article className="mod-card" key={mod.path}>
              <span className={`mod-status-icon${mod.enabled ? " is-enabled" : ""}`}><PuzzlePiece size={19} /></span>
              <div className="mod-card-info">
                <div className="task-title-row">
                  <h3>{mod.name || mod.path.split("/").pop()}</h3>
                  <span className="status-badge">{mod.folder === "plugins" ? "Plugin" : "Mod"}</span>
                  <span className={`status-badge ${mod.enabled ? "is-active" : "is-paused"}`}>{mod.enabled ? "Aktiv" : "Deaktiviert"}</span>
                </div>
                <p>{mod.path}</p>
              </div>
              {mod.toggleable && (
                <button className="btn" disabled={working !== ""} onClick={() => void toggle(mod)} type="button">
                  {working === mod.path ? <ArrowClockwise size={16} /> : mod.enabled ? <Pause size={16} /> : <Play size={16} />}
                  {mod.enabled ? "Deaktivieren" : "Aktivieren"}
                </button>
              )}
            </article>
          ))}
        </div>
      )}
    </div>
  );
}
