import { useCallback, useEffect, useRef, useState } from "react";
import { ArrowClockwise, Circle, UsersThree } from "@phosphor-icons/react";
import { dashboardRequest, jsonBody } from "../lib/hostApi";

interface PlayerLists {
  whitelist: string[];
  ops: string[];
  "banned-players": string[];
  online: string[];
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
  const [refreshError, setRefreshError] = useState("");
  const [refreshing, setRefreshing] = useState(false);
  const active = useRef(false);
  const inFlight = useRef(false);

  const refresh = useCallback(async (queryChild = false) => {
    if (inFlight.current) return;
    inFlight.current = true;
    setRefreshing(true);
    try {
      if (queryChild) {
        const result = await dashboardRequest<{ requested: boolean }>("/api/players/refresh", { method: "POST" });
        if (result.requested) await new Promise((resolve) => window.setTimeout(resolve, 1000));
      }
      setLists(await dashboardRequest<PlayerLists>("/api/players"));
      setRefreshError("");
    } catch (cause) {
      setRefreshError(cause instanceof Error ? cause.message : "Spielerliste konnte nicht geladen werden.");
    } finally {
      inFlight.current = false;
      setRefreshing(false);
    }
  }, []);
  useEffect(() => {
    const onPageChange = (event: Event) => {
      const selected = (event as CustomEvent<string>).detail === "players";
      if (selected && !active.current) void refresh(true);
      active.current = selected;
    };
    const interval = window.setInterval(() => {
      if (active.current && !document.hidden) void refresh();
    }, 10000);
    const onVisibilityChange = () => {
      if (active.current && !document.hidden) void refresh(true);
    };
    window.addEventListener("dashboard:tab", onPageChange);
    document.addEventListener("visibilitychange", onVisibilityChange);
    if (!document.getElementById("app")?.classList.contains("hide")
        && !document.getElementById("t-players")?.classList.contains("hide")) {
      active.current = true;
      void refresh(true);
    }
    return () => {
      window.removeEventListener("dashboard:tab", onPageChange);
      document.removeEventListener("visibilitychange", onVisibilityChange);
      window.clearInterval(interval);
    };
  }, [refresh]);

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
      if (action === "whitelist add") {
        await dashboardRequest("/api/properties/whitelist", { method: "POST", body: jsonBody({ enabled: true }) });
      }
      await sendCommand(`${action} ${name}`);
      window.dashboardUi?.notify(`Befehl an den Child gesendet: ${action} ${name}. Das Ergebnis steht in der Konsole.`);
      // Minecraft schreibt die Listen asynchron (Mojang-UUID-Abfrage), daher mehrere Nachladeversuche.
      for (const delay of [1200, 3500, 7000]) window.setTimeout(() => void refresh(delay === 1200), delay);
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
        <button className="btn" disabled={refreshing} onClick={() => void refresh(true)} type="button"><ArrowClockwise size={17} /> {refreshing ? "Aktualisiert …" : "Aktualisieren"}</button>
      </section>
      {refreshError && <p className="workflow-error" role="alert">Aktualisierung fehlgeschlagen: {refreshError}</p>}

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
          <button className="btn" disabled={busy} onClick={async () => { try { await dashboardRequest("/api/properties/whitelist", { method: "POST", body: jsonBody({ enabled: true }) }); await sendCommand("whitelist on"); window.dashboardUi?.notify("Whitelist ist an und wird beim nächsten Start beibehalten."); } catch (cause) { setError(cause instanceof Error ? cause.message : "Aktion fehlgeschlagen"); } }} type="button">Aktivieren</button>
          <button className="btn" disabled={busy} onClick={async () => { try { await dashboardRequest("/api/properties/whitelist", { method: "POST", body: jsonBody({ enabled: false }) }); await sendCommand("whitelist off"); window.dashboardUi?.notify("Whitelist ist aus und bleibt beim nächsten Start aus."); } catch (cause) { setError(cause instanceof Error ? cause.message : "Aktion fehlgeschlagen"); } }} type="button">Deaktivieren</button>
          <button className="btn" disabled={busy} onClick={async () => { try { await sendCommand("whitelist reload"); window.dashboardUi?.notify("Whitelist-Befehl gesendet. Das Ergebnis steht in der Konsole."); window.setTimeout(() => void refresh(), 1200); } catch (cause) { setError(cause instanceof Error ? cause.message : "Aktion fehlgeschlagen"); } }} type="button">Neu laden</button>
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
