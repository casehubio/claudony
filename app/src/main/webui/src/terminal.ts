import "@casehubio/pages-component-terminal/xterm.css";
import { loadSite, registerPanel } from "@casehubio/pages-runtime";
import { hostPanel, rows } from "@casehubio/pages-ui";
import { initTheme } from "./theme";

initTheme();
import "./components/terminal-header";
import "./components/terminal-workspace";
import "./components/key-bar";
import type { ClaudonyTerminalHeader } from "./components/terminal-header";
import type { ClaudonyTerminalWorkspace } from "./components/terminal-workspace";
import { fetchWithAuth } from "./util/auth";

registerPanel("terminal-header", "claudony-terminal-header");
registerPanel("terminal-workspace", "claudony-terminal-workspace");
registerPanel("key-bar", "claudony-key-bar");

const app = rows(
  hostPanel("terminal-header"),
  hostPanel("terminal-workspace"),
  hostPanel("key-bar"),
);

const container = document.getElementById("app");
if (!container) throw new Error("Missing #app container");

loadSite(container, app).then(() => {
  const params = new URLSearchParams(window.location.search);
  const sessionId = params.get("id");
  const sessionName = params.get("name") || "Session";
  const proxyPeer = params.get("proxyPeer") || undefined;
  const channel = params.get("channel") || undefined;

  if (!sessionId) {
    window.location.href = "/app/";
    return;
  }

  const header = document.querySelector("claudony-terminal-header") as ClaudonyTerminalHeader;
  const workspace = document.querySelector("claudony-terminal-workspace") as ClaudonyTerminalWorkspace;

  // Set title
  document.title = sessionName + " — RemoteCC";

  // Configure header
  header.configure({ sessionName, sessionId });

  // Listen for terminal lifecycle events
  document.addEventListener("pages-event", ((e: CustomEvent) => {
    const { topic, payload } = e.detail;
    switch (topic) {
      case "terminal-connected":
        header.updateStatus("connected", "active");
        break;
      case "terminal-disconnected":
        if (payload.reason === "session-expired") {
          header.updateStatus("session expired", "idle");
        } else {
          header.updateStatus("reconnecting…", "idle");
        }
        break;
      case "session-changed":
        header.configure({ sessionName: payload.sessionName, sessionId: payload.sessionId });
        document.title = payload.sessionName + " — RemoteCC";
        break;
      case "compose-requested":
        openCompose();
        break;
    }
  }) as EventListener);

  // Header button wiring
  header.querySelector("#workers-toggle-btn")!.addEventListener("click", () => workspace.toggleWorkers());
  header.querySelector("#ch-toggle-btn")!.addEventListener("click", () => workspace.toggleChannels());
  header.querySelector("#compose-btn")!.addEventListener("click", () => openCompose());

  // Fetch session and configure workspace
  fetchWithAuth("/api/sessions/" + sessionId)
    .then(r => r.ok ? r.json() : null)
    .then((session: { caseId?: string; roleName?: string; status?: string; createdAt?: string } | null) => {
      workspace.configure({
        sessionId,
        sessionName,
        proxyPeer,
        caseId: session?.caseId,
        roleName: session?.roleName,
        status: session?.status,
        createdAt: session?.createdAt,
        channel,
      });
    })
    .catch(() => {
      workspace.configure({ sessionId, sessionName, proxyPeer, channel });
    });

  // ── Compose overlay ────────────────────────────────────────────────────

  const overlay = document.createElement("div");
  overlay.id = "compose-overlay";
  overlay.className = "compose-overlay hidden";
  overlay.innerHTML = `
    <div class="compose-dialog">
      <div class="compose-header">
        <span>Compose</span>
        <span class="compose-hint">Ctrl+Enter to send · Esc to cancel</span>
      </div>
      <textarea id="compose-textarea" class="compose-textarea" placeholder="Type your message here — full mouse editing supported" rows="6" spellcheck="false"></textarea>
      <div class="compose-actions">
        <button id="compose-send-btn" class="compose-send">Send</button>
        <button id="compose-cancel-btn" class="compose-cancel">Cancel</button>
      </div>
    </div>
  `;
  document.body.appendChild(overlay);

  const composeTextarea = overlay.querySelector("#compose-textarea") as HTMLTextAreaElement;

  function openCompose(): void {
    overlay.classList.remove("hidden");
    composeTextarea.focus();
    composeTextarea.select();
  }

  function closeCompose(): void {
    overlay.classList.add("hidden");
    workspace.getTerminal()?.terminal?.focus();
  }

  function sendCompose(): void {
    const text = composeTextarea.value;
    if (!text) { closeCompose(); return; }
    composeTextarea.value = "";
    closeCompose();
    workspace.getTerminal()?.paste(text);
  }

  overlay.querySelector("#compose-send-btn")!.addEventListener("click", sendCompose);
  overlay.querySelector("#compose-cancel-btn")!.addEventListener("click", closeCompose);

  composeTextarea.addEventListener("keydown", (e) => {
    if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) { e.preventDefault(); sendCompose(); }
    if (e.key === "Escape") { e.preventDefault(); closeCompose(); }
  });

  overlay.addEventListener("click", (e) => {
    if (e.target === overlay) closeCompose();
  });

  // ── Global keyboard shortcuts ──────────────────────────────────────────

  document.addEventListener("keydown", (e) => {
    if (e.ctrlKey && e.key === "g" && overlay.classList.contains("hidden")) {
      e.preventDefault();
      openCompose();
    }
    if (e.ctrlKey && e.key === "k") {
      e.preventDefault();
      workspace.toggleChannels();
    }
  });

  // ── Lifecycle cleanup ──────────────────────────────────────────────────

  window.addEventListener("beforeunload", () => {
    workspace.destroy();
  });

}).catch(console.error);
