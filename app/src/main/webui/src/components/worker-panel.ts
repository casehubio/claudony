import { escapeHtml } from "../util/auth";

interface WorkerInfo {
  id: string;
  name: string;
  status: string;
  roleName?: string;
  createdAt: string;
}

export class ClaudonyWorkerPanel extends HTMLElement {
  private _sessionId = "";
  private _eventSource: EventSource | null = null;
  private _collapsed = true;

  connectedCallback(): void {
    this.render();
  }

  configure(opts: { sessionId: string }): void {
    this._sessionId = opts.sessionId;
    if (this._eventSource) {
      this._eventSource.close();
      this._eventSource = null;
    }
    if (!this._collapsed) {
      this.connectSSE();
    }
  }

  destroy(): void {
    if (this._eventSource) {
      this._eventSource.close();
      this._eventSource = null;
    }
  }

  open(): void {
    this._collapsed = false;
    this.classList.remove("collapsed");
    if (this._sessionId && !this._eventSource) {
      this.connectSSE();
    }
  }

  close(): void {
    this._collapsed = true;
    this.classList.add("collapsed");
    if (this._eventSource) {
      this._eventSource.close();
      this._eventSource = null;
    }
  }

  toggle(): void {
    if (this._collapsed) this.open();
    else this.close();
  }

  private connectSSE(): void {
    if (this._eventSource) this._eventSource.close();

    this._eventSource = new EventSource(
      "/api/sessions/" + this._sessionId + "/case-events"
    );

    this._eventSource.onmessage = (e) => {
      this.renderWorkers(JSON.parse(e.data) as WorkerInfo[]);
    };

    this._eventSource.onerror = () => {
      // EventSource reconnects automatically — no manual retry
    };

    if ((window as unknown as Record<string, unknown>).__CLAUDONY_TEST_MODE__) {
      (window as unknown as Record<string, unknown>)._caseEventSource = this._eventSource;
    }
  }

  private render(): void {
    this.classList.add("collapsed");

    this.innerHTML = `
      <style>
        :host, claudony-worker-panel {
          width: 240px; min-width: 240px;
          background: var(--surface); border-right: 1px solid var(--border);
          display: flex; flex-direction: column;
          transition: width 0.2s, min-width 0.2s;
          overflow: hidden;
        }
        claudony-worker-panel.collapsed { width: 0; min-width: 0; }
        .case-panel-header {
          display: flex; align-items: center; justify-content: space-between;
          padding: 8px 12px; border-bottom: 1px solid var(--border); flex-shrink: 0;
        }
        .case-panel-title { font-size: 12px; font-weight: 600; text-transform: uppercase; color: var(--text-dim); }
        .ch-close-btn {
          background: none; border: none; color: var(--text-dim);
          font-size: 14px; cursor: pointer; padding: 2px 6px;
        }
        .ch-close-btn:hover { color: var(--text); background: transparent; }
        .case-worker-list { flex: 1; overflow-y: auto; padding: 8px 0; }
        .case-worker-row {
          display: flex; align-items: center; gap: 8px;
          padding: 6px 12px; cursor: pointer; font-size: 13px;
        }
        .case-worker-row:hover { background: rgba(255,255,255,0.04); }
        .case-worker-row.active-worker { background: rgba(0,122,204,0.12); border-left: 2px solid var(--accent); }
        .worker-status-dot {
          width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0;
        }
        .worker-status-dot.active { background: var(--active); }
        .worker-status-dot.idle { background: var(--idle); }
        .worker-status-dot.waiting { background: var(--waiting); }
        .worker-status-dot.faulted { background: var(--danger); }
        .case-worker-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
        .case-worker-time { font-size: 11px; color: var(--text-dim); }
        .case-panel-placeholder { padding: 16px; color: var(--text-dim); font-size: 13px; text-align: center; }
      </style>
      <div class="case-panel-header">
        <span class="case-panel-title">Workers</span>
        <button id="case-close-btn" class="ch-close-btn" title="Close">&#10005;</button>
      </div>
      <div id="case-worker-list" class="case-worker-list">
        <div class="case-panel-placeholder">No case assigned.</div>
      </div>
    `;

    this.querySelector("#case-close-btn")!.addEventListener("click", () => this.close());
  }

  private renderWorkers(workers: WorkerInfo[]): void {
    const list = this.querySelector("#case-worker-list")!;
    list.innerHTML = "";

    if (!workers || workers.length === 0) {
      list.innerHTML = '<div class="case-panel-placeholder">No workers found.</div>';
      return;
    }

    workers.forEach((w) => {
      const row = document.createElement("div");
      const status = (w.status || "idle").toLowerCase();
      const isActive = w.id === this._sessionId;
      row.className = "case-worker-row" + (isActive ? " active-worker" : "");

      const displayName = w.roleName || w.name.replace(/^claudony-worker-/, "").replace(/^claudony-/, "");
      const timeAgo = this.workerTimeAgo(w.createdAt);

      row.innerHTML =
        '<span class="worker-status-dot ' + escapeHtml(status) + '"></span>' +
        '<span class="case-worker-name">' + escapeHtml(displayName) + '</span>' +
        '<span class="case-worker-time">' + escapeHtml(timeAgo) + '</span>';

      row.addEventListener("click", () => {
        if (isActive) return;
        this.dispatchEvent(new CustomEvent("pages-event", {
          bubbles: true, composed: true,
          detail: { topic: "worker-selected", payload: { sessionId: w.id, name: displayName } },
        }));
      });

      list.appendChild(row);
    });
  }

  private workerTimeAgo(iso: string): string {
    const diff = Date.now() - new Date(iso).getTime();
    const m = Math.floor(diff / 60000);
    if (m < 1) return "now";
    if (m < 60) return m + "m";
    return Math.floor(m / 60) + "h";
  }
}

customElements.define("claudony-worker-panel", ClaudonyWorkerPanel);
