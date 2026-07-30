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
      case "workers-toggle":
        workspace.toggleWorkers();
        break;
      case "channels-toggle":
        workspace.toggleChannels();
        break;
    }
  }) as EventListener);

  // Header buttons dispatch pages-event — handled in the event listener above

  // Fetch session and configure workspace or workbench
  fetchWithAuth("/api/sessions/" + sessionId)
    .then(r => r.ok ? r.json() : null)
    .then(async (session: { caseId?: string; roleName?: string; status?: string; createdAt?: string } | null) => {
      if (session?.caseId) {
        const { ClaudonyWorkbench } = await import('./components/claudony-workbench.js');
        void ClaudonyWorkbench;
        const workbench = document.createElement('claudony-workbench') as InstanceType<typeof ClaudonyWorkbench>;
        workspace.replaceWith(workbench);
        workbench.configure({
          sessionId,
          sessionName,
          proxyPeer,
          caseId: session.caseId,
          roleName: session.roleName,
          status: session.status,
          createdAt: session.createdAt,
          channel,
        });

        header.hideFleetControls = true;
      } else {
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
      }
    })
    .catch(() => {
      workspace.configure({ sessionId, sessionName, proxyPeer, channel });
    });

  // ── Compose modal ──────────────────────────────────────────────────────

  import('@casehubio/pages-primitives/modal');
  import('@casehubio/pages-ui-components');

  const composeModal = document.createElement('pages-modal') as any;
  composeModal.size = 'md';
  const headerSlot = document.createElement('span');
  headerSlot.slot = 'header';
  headerSlot.textContent = 'Compose';
  composeModal.appendChild(headerSlot);

  const composeTextarea = document.createElement('pages-textarea') as any;
  composeTextarea.placeholder = 'Type your message here — full mouse editing supported';
  composeTextarea.rows = 6;
  composeModal.appendChild(composeTextarea);

  const actionsSlot = document.createElement('div');
  actionsSlot.slot = 'actions';
  const sendBtn = document.createElement('pages-button') as any;
  sendBtn.variant = 'primary'; sendBtn.label = 'Send';
  const cancelBtn = document.createElement('pages-button') as any;
  cancelBtn.variant = 'ghost'; cancelBtn.label = 'Cancel';
  actionsSlot.appendChild(cancelBtn);
  actionsSlot.appendChild(sendBtn);
  composeModal.appendChild(actionsSlot);
  document.body.appendChild(composeModal);

  let composeOpen = false;

  function openCompose(): void {
    composeOpen = true;
    composeModal.open = true;
  }

  function closeCompose(): void {
    composeOpen = false;
    composeModal.open = false;
    workspace.getTerminal()?.terminal?.focus();
  }

  function sendCompose(): void {
    const text = composeTextarea.value;
    if (!text) { closeCompose(); return; }
    composeTextarea.value = '';
    closeCompose();
    workspace.getTerminal()?.paste(text);
  }

  sendBtn.addEventListener('click', sendCompose);
  cancelBtn.addEventListener('click', closeCompose);
  composeModal.addEventListener('pages-modal-close', closeCompose);

  composeTextarea.addEventListener('keydown', (e: KeyboardEvent) => {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); sendCompose(); }
  });

  // ── Global keyboard shortcuts ──────────────────────────────────────────

  document.addEventListener("keydown", (e) => {
    if (e.ctrlKey && e.key === "g" && !composeOpen) {
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
