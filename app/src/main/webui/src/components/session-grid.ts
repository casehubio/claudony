import { timeAgo } from "../util/time";
import { fetchWithAuth, escapeHtml } from "../util/auth";
import { THEME_CSS } from "../theme";

interface Session {
  id: string;
  name: string;
  status: string;
  workingDir: string;
  lastActive: string;
  caseId?: string;
  roleName?: string;
  instanceUrl?: string;
  instanceName?: string;
  stale?: boolean;
}

const POLL_INTERVAL = 5000;

export class ClaudonySessionGrid extends HTMLElement {
  private shadow: ShadowRoot;
  private sessions: Session[] = [];
  private pollTimer: number | null = null;

  constructor() {
    super();
    this.shadow = this.attachShadow({ mode: "open" });
  }

  connectedCallback(): void {
    this.render();
    this.fetchSessions();
    this.pollTimer = window.setInterval(() => this.fetchSessions(), POLL_INTERVAL);
  }

  disconnectedCallback(): void {
    if (this.pollTimer !== null) {
      clearInterval(this.pollTimer);
      this.pollTimer = null;
    }
  }

  private async fetchSessions(): Promise<void> {
    try {
      const res = await fetchWithAuth("/api/sessions");
      if (!res.ok) return;
      this.sessions = await res.json();
      this.renderGrid();
    } catch {
      // network error — keep showing stale data
    }
  }

  private render(): void {
    this.shadow.innerHTML = `
      <style>
        ${THEME_CSS}
        :host { display: block; height: 100%; overflow-y: auto; padding: 1rem; }
        .header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 1rem; }
        .header h2 { margin: 0; font-size: 1.1rem; }
        .new-btn {
          background: var(--accent); color: #fff; border: none; padding: 0.4rem 0.8rem;
          border-radius: var(--radius); cursor: pointer; font-size: 0.85rem;
        }
        .new-btn:hover { filter: brightness(1.2); }
        .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 0.75rem; }
        .card {
          background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius);
          padding: 0.75rem; cursor: pointer; transition: border-color 0.15s;
        }
        .card:hover { border-color: var(--accent); }
        .card-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 0.4rem; }
        .card-name { font-weight: 600; font-size: 0.95rem; }
        .badge {
          font-size: 0.7rem; padding: 0.15rem 0.4rem; border-radius: 3px;
          text-transform: uppercase; font-weight: 600;
        }
        .badge-active { background: #2d4a2d; color: var(--active); }
        .badge-waiting { background: #4a3d2d; color: #e0a050; }
        .badge-idle { background: #3e3e42; color: var(--text-muted); }
        .card-dir { font-size: 0.8rem; color: var(--text-muted); margin-bottom: 0.3rem; font-family: monospace; }
        .card-time { font-size: 0.75rem; color: var(--text-muted); }
        .card-actions { display: flex; gap: 0.4rem; margin-top: 0.5rem; }
        .action-btn {
          font-size: 0.75rem; padding: 0.2rem 0.5rem; border: 1px solid var(--border);
          background: transparent; color: var(--text-muted); border-radius: 3px; cursor: pointer;
        }
        .action-btn:hover { border-color: var(--accent); color: var(--accent); }
        .action-btn.danger:hover { border-color: var(--danger); color: var(--danger); }
        .stale { opacity: 0.6; }
        .stale-badge { font-size: 0.7rem; color: #e0a050; margin-bottom: 0.3rem; }
        .instance-badge { font-size: 0.7rem; background: #333; padding: 0.1rem 0.3rem; border-radius: 3px; margin-left: 0.4rem; }
        .empty { text-align: center; color: var(--text-muted); padding: 3rem 1rem; }
      </style>
      <div class="header">
        <h2>Sessions</h2>
        <button class="new-btn" id="new-btn">+ New Session</button>
      </div>
      <div class="grid" id="grid"></div>
    `;

    this.shadow.getElementById("new-btn")!.addEventListener("click", () => this.showNewSessionDialog());
  }

  private renderGrid(): void {
    const grid = this.shadow.getElementById("grid")!;
    if (this.sessions.length === 0) {
      grid.innerHTML = '<div class="empty">No sessions running</div>';
      return;
    }

    grid.innerHTML = this.sessions.map((s) => {
      const name = s.name.replace(/^claudony-/, "");
      const escapedName = escapeHtml(name);
      const escapedWorkingDir = escapeHtml(s.workingDir || "");
      const escapedStatus = escapeHtml(s.status);
      const instanceDisplay = s.instanceName || s.instanceUrl || "";
      const escapedInstanceDisplay = escapeHtml(instanceDisplay);
      const status = s.status.toLowerCase();
      const badgeClass = status === "active" ? "badge-active" : status === "waiting" ? "badge-waiting" : "badge-idle";
      const instanceBadge = s.instanceUrl
        ? `<span class="instance-badge">${escapedInstanceDisplay}</span>`
        : "";
      const staleBadge = s.stale ? `<div class="stale-badge">⏰ last seen ${timeAgo(s.lastActive)}</div>` : "";

      return `
        <div class="card ${s.stale ? "stale" : ""}" data-id="${s.id}" data-name="${encodeURIComponent(name)}">
          <div class="card-header">
            <span class="card-name">${escapedName}${instanceBadge}</span>
            <span class="badge ${badgeClass}">${escapedStatus}</span>
          </div>
          ${staleBadge}
          <div class="card-dir">${escapedWorkingDir}</div>
          <div class="card-time">Active ${timeAgo(s.lastActive)}</div>
          <div class="card-actions">
            <button class="action-btn open-btn">Open</button>
            <button class="action-btn danger delete-btn" data-id="${s.id}">Delete</button>
          </div>
        </div>
      `;
    }).join("");

    grid.querySelectorAll(".card").forEach((card) => {
      const el = card as HTMLElement;
      const id = el.dataset.id!;
      const name = decodeURIComponent(el.dataset.name!);

      el.querySelector(".open-btn")!.addEventListener("click", (e) => {
        e.stopPropagation();
        window.location.href = `/app/session.html?id=${id}&name=${encodeURIComponent(name)}`;
      });

      el.querySelector(".delete-btn")!.addEventListener("click", (e) => {
        e.stopPropagation();
        this.deleteSession(id);
      });

      el.addEventListener("click", () => {
        window.location.href = `/app/session.html?id=${id}&name=${encodeURIComponent(name)}`;
      });
    });
  }

  private async deleteSession(id: string): Promise<void> {
    if (!confirm("Delete this session?")) return;
    await fetchWithAuth(`/api/sessions/${id}`, { method: "DELETE" });
    this.fetchSessions();
  }

  private showNewSessionDialog(): void {
    const name = prompt("Session name:");
    if (!name) return;
    const workingDir = prompt("Working directory (leave blank for default):");

    const body: Record<string, string> = { name };
    if (workingDir) body.workingDir = workingDir;

    fetchWithAuth("/api/sessions", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    }).then(() => this.fetchSessions());
  }
}

customElements.define("claudony-session-grid", ClaudonySessionGrid);
