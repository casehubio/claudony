import { escapeHtml } from "../util/auth";

export class ClaudonyTerminalHeader extends HTMLElement {
  private _sessionName = "Session";
  private _sessionId = "";

  connectedCallback(): void {
    this.render();
  }

  configure(opts: { sessionName?: string; sessionId?: string }): void {
    if (opts.sessionName !== undefined) this._sessionName = opts.sessionName;
    if (opts.sessionId !== undefined) this._sessionId = opts.sessionId;

    const nameEl = this.querySelector("#session-name");
    if (nameEl) {
      // DOM already exists — update text only, preserve event listeners
      nameEl.textContent = this._sessionName;
      document.title = this._sessionName;
    } else {
      // First configure — render the full DOM
      this.render();
    }
  }

  updateStatus(text: string, cssClass: string): void {
    const badge = this.querySelector("#status-badge");
    if (badge) {
      badge.textContent = text;
      badge.className = "badge " + cssClass;
    }
  }

  private render(): void {
    this.innerHTML = `
      <style>
        .terminal-header {
          display: flex; align-items: center; gap: 12px;
          padding: 10px 16px; background: var(--surface);
          border-bottom: 1px solid var(--border); flex-shrink: 0;
        }
        #back-btn { color: var(--accent); text-decoration: none; font-size: 14px; white-space: nowrap; }
        #back-btn:hover { text-decoration: underline; }
        #session-name { font-weight: 600; font-size: 14px; flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
        .compose-btn {
          background: #2a2a4a; color: var(--text); border: none; padding: 6px 12px;
          border-radius: var(--radius); cursor: pointer; font-size: 12px;
        }
        .compose-btn:hover { background: #3a3a5c; }
      </style>
      <header class="terminal-header">
        <a href="/app/" id="back-btn">&#8592; Dashboard</a>
        <span id="session-name">${escapeHtml(this._sessionName)}</span>
        <span id="status-badge" class="badge idle">connecting</span>
        <button id="compose-btn" class="compose-btn" title="Compose text (Ctrl+G)">Compose</button>
        <button id="workers-toggle-btn" class="compose-btn" title="Toggle workers panel">Workers</button>
        <button id="ch-toggle-btn" class="compose-btn" title="Toggle channel panel (Ctrl+K)">Channels</button>
      </header>
    `;
    document.title = this._sessionName;
  }
}

customElements.define("claudony-terminal-header", ClaudonyTerminalHeader);
