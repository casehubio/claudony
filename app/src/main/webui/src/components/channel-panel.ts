import { escapeHtml } from "../util/auth";

interface ChannelPanelConfig {
  sessionId: string;
  caseId?: string;
  roleName?: string;
  createdAt?: string;
  channel?: string;
  status?: string;
}

interface ChannelInfo {
  name: string;
  message_count?: number;
  allowedTypes?: string | null;
}

interface TimelineEntry {
  id?: number;
  type?: string;
  message_type?: string;
  sender?: string;
  content?: string | null;
  created_at?: string;
  agent_id?: string;
  tool_name?: string;
  duration_ms?: number | null;
  token_count?: number | null;
}

interface WorkerSummary {
  workerId?: string;
  workerName?: string;
  startedAt?: string;
  completedAt?: string;
}

interface TypeOption {
  value: string;
  label: string;
}

const POLL_MS = 3000;
const CURSOR_STORE_KEY = "claudony.channel.cursors";

const MSG_BADGE_LABELS: Record<string, string> = {
  query: "QUERY",
  command: "COMMAND",
  response: "RESPONSE",
  status: "STATUS",
  decline: "DECLINE",
  handoff: "HANDOFF",
  done: "DONE",
  failure: "FAILURE",
  event: "EVENT",
};

const TERMINAL_TYPES: Record<string, number> = {
  decline: 1,
  handoff: 1,
  done: 1,
  failure: 1,
};

const TYPE_OPTIONS: TypeOption[] = [
  { value: "command", label: "COMMAND — directive" },
  { value: "query", label: "QUERY — request info" },
  { value: "status", label: "STATUS — update" },
  { value: "response", label: "RESPONSE — answer" },
  { value: "done", label: "DONE — completed" },
  { value: "decline", label: "DECLINE — refuse" },
  { value: "handoff", label: "HANDOFF — delegate" },
  { value: "event", label: "EVENT — observation" },
];

export class ClaudonyChannelPanel extends HTMLElement {
  private _sessionId = "";
  private _caseId: string | null = null;
  private _roleName: string | null = null;
  private _sessionStatus: string | null = null;
  private _sessionCreatedAt: Date | null = null;
  private _preselect: string | null = null;

  private _chSelectedName: string | null = null;
  private _chPollTimer: ReturnType<typeof setTimeout> | null = null;
  private _chEventSource: EventSource | null = null;
  private _chStalenessMs = 30 * 60 * 1000;
  private _chCursors: Record<string, { id: number; ts: number }> = {};
  private _chChannelAllowedTypes: Record<string, string | null> = {};

  private _chCaseHeaderEl: HTMLElement | null = null;
  private _chLineageEl: HTMLElement | null = null;
  private _lineageExpanded = false;
  private _lineagePollTimer: ReturnType<typeof setTimeout> | null = null;
  private _elapsedTicker: ReturnType<typeof setInterval> | null = null;

  private _chStalePromptEl: HTMLElement | null = null;
  private _chStalePromptCatchupBtn: HTMLButtonElement | null = null;
  private _chStalePromptReloadBtn: HTMLButtonElement | null = null;

  // DOM references
  private _chSelect: HTMLSelectElement | null = null;
  private _chFeed: HTMLElement | null = null;
  private _chInput: HTMLTextAreaElement | null = null;
  private _chTypeSelect: HTMLSelectElement | null = null;
  private _chSendBtn: HTMLButtonElement | null = null;
  private _chError: HTMLElement | null = null;

  connectedCallback(): void {
    this.render();
    this.loadCursorsFromStorage();
    this.fetchMeshConfig();
  }

  configure(opts: ChannelPanelConfig): void {
    this._sessionId = opts.sessionId;
    this._caseId = opts.caseId || null;
    this._roleName = opts.roleName || null;
    this._sessionCreatedAt = opts.createdAt ? new Date(opts.createdAt) : null;
    this._preselect = opts.channel || null;
    this._sessionStatus = opts.status || null;
  }

  destroy(): void {
    if (this._chEventSource) {
      this._chEventSource.close();
      this._chEventSource = null;
    }
    if (this._chPollTimer) {
      clearTimeout(this._chPollTimer);
      this._chPollTimer = null;
    }
    if (this._lineagePollTimer) {
      clearTimeout(this._lineagePollTimer);
      this._lineagePollTimer = null;
    }
    if (this._elapsedTicker) {
      clearInterval(this._elapsedTicker);
      this._elapsedTicker = null;
    }
    this._chStalePromptCatchupBtn = null;
    this._chStalePromptReloadBtn = null;
  }

  open(): void {
    this.classList.remove("collapsed");
    if (this._caseId && !this._chCaseHeaderEl) {
      this.renderCaseHeader();
      this.loadLineage();
    }
    this.loadChannels();
  }

  close(): void {
    this.hideStalePrompt();
    this.classList.add("collapsed");
    if (this._chPollTimer) {
      clearTimeout(this._chPollTimer);
      this._chPollTimer = null;
    }
    if (this._chEventSource) {
      this._chEventSource.close();
      this._chEventSource = null;
    }
    if (this._lineagePollTimer) {
      clearTimeout(this._lineagePollTimer);
      this._lineagePollTimer = null;
    }
    if (this._elapsedTicker) {
      clearInterval(this._elapsedTicker);
      this._elapsedTicker = null;
    }
  }

  toggle(): void {
    if (this.classList.contains("collapsed")) {
      this.open();
    } else {
      this.close();
    }
  }

  private loadCursorsFromStorage(): void {
    try {
      const stored = sessionStorage.getItem(CURSOR_STORE_KEY);
      if (stored) this._chCursors = JSON.parse(stored);
    } catch (_e) {
      // ignore
    }
  }

  private fetchMeshConfig(): void {
    fetch("/api/mesh/config")
      .then((r) => r.json())
      .then((cfg: { cursorStalenessMinutes?: number }) => {
        if (cfg && typeof cfg.cursorStalenessMinutes === "number") {
          this._chStalenessMs = cfg.cursorStalenessMinutes * 60 * 1000;
        }
      })
      .catch(() => {
        // ignore
      });
  }

  private render(): void {
    this.classList.add("channel-panel", "collapsed");

    this.innerHTML = `
      <style>
        claudony-channel-panel {
          width: 300px;
          min-width: 300px;
          display: flex;
          flex-direction: column;
          background: var(--surface);
          border-left: 1px solid var(--border);
          overflow: hidden;
          transition: width 0.2s ease, min-width 0.2s ease;
          flex-shrink: 0;
        }
        claudony-channel-panel.collapsed { width: 0; min-width: 0; }

        .ch-panel-header {
          display: flex;
          align-items: center;
          gap: 6px;
          padding: 8px 10px;
          border-bottom: 1px solid var(--border);
          flex-shrink: 0;
        }
        .ch-select {
          flex: 1;
          background: var(--bg);
          color: var(--text);
          border: 1px solid var(--border);
          border-radius: var(--radius);
          padding: 4px 6px;
          font-size: 12px;
        }
        .ch-select:focus { outline: none; border-color: var(--accent); }
        .ch-close-btn {
          background: transparent;
          border: none;
          color: var(--text-dim);
          padding: 3px 6px;
          font-size: 13px;
          cursor: pointer;
          flex-shrink: 0;
        }
        .ch-close-btn:hover { color: var(--text); background: transparent; }

        /* Message feed */
        .ch-feed {
          flex: 1;
          overflow-y: auto;
          padding: 8px;
          display: flex;
          flex-direction: column;
          gap: 6px;
        }

        /* Individual message */
        .ch-msg {
          display: flex;
          flex-direction: column;
          gap: 2px;
          padding: 5px 7px;
          border-radius: 4px;
          font-size: 12px;
          border: 1px solid transparent;
        }
        .ch-msg-meta { display: flex; align-items: center; gap: 5px; flex-wrap: wrap; }
        .ch-msg-content {
          color: var(--text);
          line-height: 1.4;
          word-break: break-word;
          white-space: pre-wrap;
        }
        .ch-msg-sender {
          font-size: 11px;
          font-weight: 600;
        }
        .ch-sender-human { color: #f0c27f; }
        .ch-sender-agent { color: var(--active); }
        .ch-msg-time { font-size: 10px; color: var(--text-dim); margin-left: auto; }

        /* Human-posted messages get a subtle highlight */
        .ch-msg.ch-msg-human { background: rgba(240,194,127,.05); border-color: rgba(240,194,127,.15); }

        /* Terminal messages (DONE, FAILURE, DECLINE, HANDOFF) */
        .ch-msg.ch-msg-terminal { opacity: 0.8; }

        /* EVENT messages are dimmed */
        .ch-msg.ch-msg-event { opacity: 0.55; }
        .ch-msg.ch-msg-event .ch-msg-content { font-style: italic; font-size: 11px; }

        /* Normative message type badges */
        .msg-badge {
          font-size: 9px;
          font-weight: 700;
          padding: 1px 5px;
          border-radius: 3px;
          letter-spacing: 0.04em;
          flex-shrink: 0;
        }
        .msg-badge.msg-query   { background: rgba(0,122,204,.2);   color: #4fc3f7; }
        .msg-badge.msg-command { background: rgba(215,166,95,.2);  color: #d7a65f; }
        .msg-badge.msg-response{ background: rgba(78,201,176,.2);  color: var(--active); }
        .msg-badge.msg-status  { background: rgba(136,136,136,.2); color: var(--text-dim); }
        .msg-badge.msg-decline { background: rgba(244,71,71,.2);   color: var(--danger); }
        .msg-badge.msg-handoff { background: rgba(197,134,192,.2); color: #c586c0; }
        .msg-badge.msg-done    { background: rgba(106,153,85,.2);  color: #6a9955; }
        .msg-badge.msg-failure { background: rgba(206,64,64,.2);   color: #ce4040; }
        .msg-badge.msg-event   { background: rgba(80,80,80,.3);    color: #666; }

        /* Interjection dock */
        .ch-dock {
          border-top: 1px solid var(--border);
          padding: 7px 8px;
          display: flex;
          flex-direction: column;
          gap: 5px;
          flex-shrink: 0;
        }
        .ch-dock-row { display: flex; gap: 4px; }
        .ch-type-select {
          flex: 1;
          background: var(--bg);
          color: var(--text);
          border: 1px solid var(--border);
          border-radius: 3px;
          font-size: 11px;
          padding: 3px 5px;
        }
        .ch-input {
          width: 100%;
          resize: none;
          background: var(--bg);
          color: var(--text);
          border: 1px solid var(--border);
          border-radius: 3px;
          padding: 5px 7px;
          font-size: 12px;
          font-family: inherit;
          line-height: 1.4;
        }
        .ch-input:focus { outline: none; border-color: var(--accent); }
        .ch-dock-footer { display: flex; align-items: center; gap: 6px; }
        .ch-send-btn {
          font-size: 12px;
          padding: 4px 12px;
          background: var(--accent);
          flex-shrink: 0;
        }
        .ch-send-btn:disabled { opacity: 0.4; cursor: default; }
        .ch-error { font-size: 11px; color: var(--danger); flex: 1; }

        /* Channel panel empty state */
        .ch-empty {
          color: var(--text-dim);
          font-size: 12px;
          text-align: center;
          padding: 20px 8px;
          font-style: italic;
        }

        /* Stale cursor reconnect prompt */
        .ch-stale-prompt {
          display: flex;
          flex-direction: column;
          gap: 6px;
          padding: 10px 8px;
          background: rgba(240,194,127,.07);
          border-bottom: 1px solid rgba(240,194,127,.2);
          font-size: 12px;
        }
        .ch-stale-msg { color: var(--text-dim); font-style: italic; }
        .ch-stale-btn {
          background: rgba(255,255,255,.08);
          border: 1px solid rgba(255,255,255,.15);
          color: var(--text);
          padding: 4px 8px;
          font-size: 11px;
          border-radius: 3px;
          cursor: pointer;
          text-align: left;
        }
        .ch-stale-btn:hover { background: rgba(255,255,255,.14); }
        .ch-stale-btn-secondary { opacity: 0.7; }

        /* Case context header */
        .ch-case-header {
          border-bottom: 1px solid var(--border);
          padding: 6px 10px 4px;
          flex-shrink: 0;
        }
        .ch-case-info {
          display: flex;
          align-items: center;
          gap: 6px;
          margin-bottom: 4px;
        }
        .ch-case-role {
          font-size: 12px;
          font-weight: 600;
          color: var(--text);
          flex: 1;
          overflow: hidden;
          text-overflow: ellipsis;
          white-space: nowrap;
        }
        .ch-case-elapsed {
          font-size: 11px;
          color: var(--text-dim);
          flex-shrink: 0;
        }
        .ch-lineage-toggle {
          display: flex;
          align-items: center;
          gap: 5px;
          cursor: pointer;
          padding: 2px 0;
          font-size: 11px;
          color: var(--text-dim);
          user-select: none;
        }
        .ch-lineage-toggle:hover { color: var(--text); }
        .ch-lineage-chevron {
          font-size: 9px;
          transition: transform 0.15s ease;
          display: inline-block;
        }

        /* Lineage section (collapsible) */
        .ch-lineage {
          border-bottom: 1px solid var(--border);
          padding: 4px 10px;
          flex-shrink: 0;
          overflow: hidden;
        }
        .ch-lineage.ch-lineage-hidden { display: none; }
        .ch-lineage-empty {
          font-size: 11px;
          color: var(--text-dim);
          font-style: italic;
          padding: 2px 0;
        }
        .ch-lineage-row {
          display: flex;
          align-items: center;
          gap: 6px;
          padding: 3px 0;
          font-size: 11px;
          border-bottom: 1px solid var(--border);
        }
        .ch-lineage-row:last-child { border-bottom: none; }
        .ch-lineage-name {
          color: var(--active);
          font-weight: 600;
          flex: 1;
          overflow: hidden;
          text-overflow: ellipsis;
          white-space: nowrap;
        }
        .ch-lineage-time {
          color: var(--text-dim);
          flex-shrink: 0;
          font-family: var(--mono);
        }

        .worker-status-dot {
          width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; display: inline-block;
        }
        .worker-status-dot.active { background: var(--active); }
        .worker-status-dot.idle { background: var(--idle); }
        .worker-status-dot.waiting { background: var(--waiting); }
        .worker-status-dot.faulted { background: var(--danger); }
      </style>
      <div class="ch-panel-header">
        <select id="ch-select" class="ch-select">
          <option value="">— select channel —</option>
        </select>
        <button id="ch-close-btn" class="ch-close-btn" title="Close">&#10005;</button>
      </div>
      <div id="ch-feed" class="ch-feed"></div>
      <div class="ch-dock">
        <div class="ch-dock-row">
          <select id="ch-type-select" class="ch-type-select"></select>
        </div>
        <textarea id="ch-input" class="ch-input" rows="2" placeholder="Type a message…"></textarea>
        <div class="ch-dock-footer">
          <span id="ch-error" class="ch-error"></span>
          <button id="ch-send-btn" class="ch-send-btn" disabled>Send (Ctrl+Enter)</button>
        </div>
      </div>
    `;

    this._chSelect = this.querySelector("#ch-select") as HTMLSelectElement;
    this._chFeed = this.querySelector("#ch-feed")!;
    this._chInput = this.querySelector("#ch-input") as HTMLTextAreaElement;
    this._chTypeSelect = this.querySelector("#ch-type-select") as HTMLSelectElement;
    this._chSendBtn = this.querySelector("#ch-send-btn") as HTMLButtonElement;
    this._chError = this.querySelector("#ch-error")!;

    // Populate type select with all options initially
    this.updateTypeSelectForChannel(null);

    // Wire up event listeners
    this.querySelector("#ch-close-btn")!.addEventListener("click", () => this.close());

    this._chSelect.addEventListener("change", () => {
      this.selectChannel(this._chSelect!.value || null);
    });

    this._chInput.addEventListener("input", () => {
      this._chSendBtn!.disabled = !this._chSelectedName || !this._chInput!.value.trim();
    });

    this._chInput.addEventListener("keydown", (e: KeyboardEvent) => {
      if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) {
        e.preventDefault();
        this._chSendBtn!.click();
      }
    });

    this._chSendBtn.addEventListener("click", () => {
      this.sendMessage();
    });
  }

  private updateTypeSelectForChannel(channelName: string | null): void {
    const typeSelect = this._chTypeSelect;
    if (!typeSelect) return;

    const allowed = channelName ? this._chChannelAllowedTypes[channelName] : null;
    const permitted = allowed
      ? allowed.toLowerCase().split(",").map((t: string) => t.trim())
      : null;
    const current = typeSelect.value;
    typeSelect.innerHTML = "";

    TYPE_OPTIONS.forEach((opt) => {
      if (!permitted || permitted.indexOf(opt.value) !== -1) {
        const o = document.createElement("option");
        o.value = opt.value;
        o.textContent = opt.label;
        typeSelect.appendChild(o);
      }
    });

    // Restore selection if still available; otherwise default to first option
    if (typeSelect.querySelector('option[value="' + current + '"]')) {
      typeSelect.value = current;
    } else {
      typeSelect.value = typeSelect.options[0] ? typeSelect.options[0].value : "command";
    }
  }

  private formatTime(iso: string | undefined): string {
    if (!iso) return "";
    try {
      const d = new Date(iso);
      return d.toLocaleTimeString([], {
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit",
      });
    } catch (_e) {
      return "";
    }
  }

  private caseElapsed(fromMs: number): string {
    const diffM = Math.floor((Date.now() - fromMs) / 60000);
    if (diffM < 1) return "<1m";
    if (diffM < 60) return diffM + "m";
    return Math.floor(diffM / 60) + "h " + (diffM % 60) + "m";
  }

  private renderCaseHeader(): void {
    if (this._chCaseHeaderEl) this._chCaseHeaderEl.remove();
    this._chCaseHeaderEl = document.createElement("div");
    this._chCaseHeaderEl.className = "ch-case-header";

    const role = this._roleName
      ? this._roleName.replace(/^claudony-worker-/, "")
      : "—";
    const status = (this._sessionStatus || "idle").toLowerCase();
    const elapsed = this._sessionCreatedAt
      ? this.caseElapsed(this._sessionCreatedAt.getTime())
      : "—";

    this._chCaseHeaderEl.innerHTML =
      '<div class="ch-case-info">' +
      '<span class="ch-case-role">' + escapeHtml(role) + "</span>" +
      '<span class="worker-status-dot ' + escapeHtml(status) + '"></span>' +
      '<span class="ch-case-elapsed">' + escapeHtml(elapsed) + "</span>" +
      "</div>" +
      '<div class="ch-lineage-toggle">' +
      '<span class="ch-lineage-chevron">▶</span>' +
      '<span class="ch-lineage-count">Loading…</span>' +
      "</div>";

    // Insert before the feed
    const feed = this._chFeed;
    if (feed && feed.parentNode) {
      feed.parentNode.insertBefore(this._chCaseHeaderEl, feed);
    }

    this._chCaseHeaderEl.querySelector(".ch-lineage-toggle")!
      .addEventListener("click", () => this.toggleLineage());

    if (this._elapsedTicker) clearInterval(this._elapsedTicker);
    this._elapsedTicker = setInterval(() => {
      const el = this._chCaseHeaderEl && this._chCaseHeaderEl.querySelector(".ch-case-elapsed");
      if (el && this._sessionCreatedAt) {
        el.textContent = this.caseElapsed(this._sessionCreatedAt.getTime());
      }
    }, 30000);
  }

  private toggleLineage(): void {
    this._lineageExpanded = !this._lineageExpanded;
    if (this._chLineageEl) {
      this._chLineageEl.classList.toggle("ch-lineage-hidden", !this._lineageExpanded);
    }
    const chevron = this._chCaseHeaderEl && this._chCaseHeaderEl.querySelector(".ch-lineage-chevron") as HTMLElement | null;
    if (chevron) {
      chevron.style.transform = this._lineageExpanded ? "rotate(90deg)" : "";
    }
  }

  private renderLineage(workers: WorkerSummary[]): void {
    const countEl = this._chCaseHeaderEl && this._chCaseHeaderEl.querySelector(".ch-lineage-count");
    const n = workers.length;
    if (countEl) countEl.textContent = n + " prior worker" + (n === 1 ? "" : "s");

    if (this._chLineageEl) this._chLineageEl.remove();
    this._chLineageEl = document.createElement("div");
    this._chLineageEl.className = "ch-lineage ch-lineage-hidden";

    if (n === 0) {
      this._chLineageEl.innerHTML = '<div class="ch-lineage-empty">No prior workers</div>';
    } else {
      workers.forEach((w) => {
        const row = document.createElement("div");
        row.className = "ch-lineage-row";
        const name = (w.workerName || w.workerId || "?").replace(/^claudony-worker-/, "");
        const start = w.startedAt
          ? new Date(w.startedAt).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })
          : "?";
        const end = w.completedAt
          ? new Date(w.completedAt).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })
          : "?";
        const durMs =
          w.startedAt && w.completedAt
            ? new Date(w.completedAt).getTime() - new Date(w.startedAt).getTime()
            : 0;
        const dur = durMs > 0 ? Math.ceil(durMs / 60000) + "m" : "?";
        row.innerHTML =
          '<span class="ch-lineage-name">' + escapeHtml(name) + "</span>" +
          '<span class="ch-lineage-time">' + escapeHtml(start) + "→" + escapeHtml(end) +
          " (" + escapeHtml(dur) + ")</span>";
        this._chLineageEl!.appendChild(row);
      });
    }

    // Insert before the feed
    const feed = this._chFeed;
    if (feed && feed.parentNode) {
      feed.parentNode.insertBefore(this._chLineageEl, feed);
    }
    if (this._lineageExpanded) this._chLineageEl.classList.remove("ch-lineage-hidden");
  }

  private loadLineage(): void {
    fetch("/api/sessions/" + this._sessionId + "/lineage")
      .then((r) => (r.ok ? r.json() : []))
      .then((workers: WorkerSummary[]) => this.renderLineage(workers))
      .catch(() => this.renderLineage([]));

    if (this._lineagePollTimer) clearTimeout(this._lineagePollTimer);
    this._lineagePollTimer = setTimeout(() => this.loadLineage(), 60000);
  }

  private selectCaseChannel(caseId: string): void {
    const chSelect = this._chSelect;
    if (!chSelect) return;

    const prefix = "case-" + caseId + "/";
    const opts = Array.from(chSelect.options);
    const workOpt = opts.find((o) => o.value === prefix + "work");
    const anyOpt = opts.find((o) => o.value.indexOf(prefix) === 0);
    const target = workOpt || anyOpt;
    if (target) {
      chSelect.value = target.value;
      this.selectChannel(target.value);
    }
  }

  private renderMessage(entry: TimelineEntry): HTMLElement {
    const el = document.createElement("div");
    el.className = "ch-msg";

    if (entry.type === "EVENT") {
      el.classList.add("ch-msg-event");
      const agentId = escapeHtml(entry.agent_id || "system");
      // EVENTs: content is null by design
      const parts: string[] = [];
      if (entry.tool_name) parts.push(escapeHtml(entry.tool_name));
      if (entry.duration_ms != null) parts.push(escapeHtml(String(entry.duration_ms)) + "ms");
      if (entry.token_count != null) parts.push(escapeHtml(String(entry.token_count)) + "tok");
      const eventDetail = parts.join(" · ") || "—";
      el.innerHTML =
        '<div class="ch-msg-meta">' +
        '<span class="msg-badge msg-event">EVENT</span>' +
        '<span class="ch-msg-sender ch-sender-agent">' + agentId + "</span>" +
        '<span class="ch-msg-time">' + this.formatTime(entry.created_at) + "</span>" +
        "</div>" +
        '<div class="ch-msg-content">' + eventDetail + "</div>";
    } else {
      const mtype = (entry.message_type || "unknown").toLowerCase();
      const label = MSG_BADGE_LABELS[mtype] || mtype.toUpperCase();
      const sender = entry.sender || "";
      const isHuman = sender === "human" || sender.indexOf("human:") === 0;
      const isTerminal = !!TERMINAL_TYPES[mtype];
      // Display the username part
      const displaySender =
        isHuman && sender.indexOf("human:") === 0
          ? sender.slice(6) || "human"
          : sender;

      if (isHuman) el.classList.add("ch-msg-human");
      if (isTerminal) el.classList.add("ch-msg-terminal");

      el.innerHTML =
        '<div class="ch-msg-meta">' +
        '<span class="msg-badge msg-' + escapeHtml(mtype) + '">' + label + "</span>" +
        '<span class="ch-msg-sender ' + (isHuman ? "ch-sender-human" : "ch-sender-agent") + '">' +
        escapeHtml(displaySender) +
        "</span>" +
        '<span class="ch-msg-time">' + this.formatTime(entry.created_at) + "</span>" +
        "</div>" +
        '<div class="ch-msg-content">' + escapeHtml(entry.content || "") + "</div>";
    }
    return el;
  }

  private persistCursors(): void {
    try {
      sessionStorage.setItem(CURSOR_STORE_KEY, JSON.stringify(this._chCursors));
    } catch (_e) {
      // ignore
    }
  }

  private appendMessages(entries: TimelineEntry[]): void {
    const feed = this._chFeed;
    if (!feed) return;

    const wasAtBottom = feed.scrollHeight - feed.scrollTop <= feed.clientHeight + 4;
    let cursorAdvanced = false;

    entries.forEach((entry) => {
      // Skip messages already rendered
      if (entry.id && document.getElementById("ch-msg-" + entry.id)) return;
      const el = this.renderMessage(entry);
      if (entry.id) el.id = "ch-msg-" + entry.id;
      feed.appendChild(el);
      if (entry.id && this._chSelectedName) {
        const c = this._chCursors[this._chSelectedName];
        if (!c || entry.id > c.id) {
          this._chCursors[this._chSelectedName] = {
            id: entry.id,
            ts: Date.now(),
          };
          cursorAdvanced = true;
        }
      }
    });

    if (cursorAdvanced) this.persistCursors();
    if (wasAtBottom) feed.scrollTop = feed.scrollHeight;
  }

  private pollChannel(): void {
    if (!this._chSelectedName) return;
    const cursor = this._chCursors[this._chSelectedName];
    const lastId = cursor ? cursor.id : 0;
    const url =
      "/api/mesh/channels/" +
      encodeURIComponent(this._chSelectedName) +
      "/timeline?limit=50" +
      (lastId ? "&after=" + lastId : "");
    fetch(url)
      .then((r) => {
        if (!r.ok) return;
        return r.json();
      })
      .then((entries: TimelineEntry[] | undefined) => {
        if (entries && entries.length) this.appendMessages(entries);
      })
      .catch(() => {
        // ignore
      });
    this._chPollTimer = setTimeout(() => this.pollChannel(), POLL_MS);
  }

  private openChannelEventSource(name: string): void {
    if (this._chEventSource) {
      this._chEventSource.close();
      this._chEventSource = null;
    }
    const cursor = this._chCursors[name];
    const afterId = cursor ? cursor.id : 0;
    const url =
      "/api/mesh/channels/" +
      encodeURIComponent(name) +
      "/events?after=" + afterId;

    this._chEventSource = new EventSource(url);
    this._chEventSource.onmessage = (e: MessageEvent) => {
      try {
        const entries = JSON.parse(e.data) as TimelineEntry[];
        // Remove "No messages yet" placeholder if present
        const empty = this._chFeed && this._chFeed.querySelector(".ch-empty");
        if (empty) empty.remove();
        if (Array.isArray(entries) && entries.length) this.appendMessages(entries);
      } catch (_err) {
        // ignore
      }
    };
    this._chEventSource.onerror = () => {
      if (this._chEventSource) {
        this._chEventSource.close();
        this._chEventSource = null;
      }
      if (this._chSelectedName) {
        this._chPollTimer = setTimeout(() => this.pollChannel(), POLL_MS);
      }
    };
  }

  private catchUp(name: string, fromId: number): void {
    const url =
      "/api/mesh/channels/" +
      encodeURIComponent(name) +
      "/timeline?limit=50&after=" + fromId;
    fetch(url)
      .then((r) => {
        if (!r.ok) return;
        return r.json();
      })
      .then((entries: TimelineEntry[] | undefined) => {
        if (entries && entries.length) this.appendMessages(entries);
      })
      .catch(() => {
        if (this._chError) {
          this._chError.textContent = "Catch-up failed — some messages may be missing.";
        }
      });
    // EventSource already open from selectChannel(); no need to open again
  }

  private fullLoad(name: string): void {
    const url =
      "/api/mesh/channels/" +
      encodeURIComponent(name) +
      "/timeline?limit=100";
    fetch(url)
      .then((r) => {
        if (!r.ok) return;
        return r.json();
      })
      .then((entries: TimelineEntry[] | undefined) => {
        if (entries && entries.length) {
          this.appendMessages(entries);
        } else {
          const empty = document.createElement("div");
          empty.className = "ch-empty";
          empty.textContent = "No messages yet.";
          if (this._chFeed) this._chFeed.appendChild(empty);
        }
      })
      .catch(() => {
        if (this._chError) {
          this._chError.textContent = "Failed to load messages.";
        }
      });
    // EventSource already open from selectChannel(); no need to open again
  }

  private hideStalePrompt(): void {
    if (this._chStalePromptEl) this._chStalePromptEl.style.display = "none";
  }

  private showStalePrompt(name: string, cursorId: number): void {
    if (!this._chStalePromptEl) {
      this._chStalePromptEl = document.createElement("div");
      this._chStalePromptEl.id = "ch-stale-prompt";
      this._chStalePromptEl.className = "ch-stale-prompt";

      const msg = document.createElement("span");
      msg.className = "ch-stale-msg";
      msg.textContent = "You were away for a while.";

      const catchupBtn = document.createElement("button");
      catchupBtn.id = "ch-stale-catchup-btn";
      catchupBtn.className = "ch-stale-btn";
      catchupBtn.textContent = "Catch up from where you left off";

      const reloadBtn = document.createElement("button");
      reloadBtn.id = "ch-stale-reload-btn";
      reloadBtn.className = "ch-stale-btn ch-stale-btn-secondary";
      reloadBtn.textContent = "Reload full history";

      this._chStalePromptCatchupBtn = catchupBtn;
      this._chStalePromptReloadBtn = reloadBtn;

      this._chStalePromptEl.appendChild(msg);
      this._chStalePromptEl.appendChild(catchupBtn);
      this._chStalePromptEl.appendChild(reloadBtn);

      const feed = this._chFeed;
      if (feed && feed.parentNode) {
        feed.parentNode.insertBefore(this._chStalePromptEl, feed);
      }
    }
    this._chStalePromptEl.style.display = "";
    this._chStalePromptCatchupBtn!.onclick = () => {
      this.hideStalePrompt();
      this.openChannelEventSource(name);
      this.catchUp(name, cursorId);
    };
    this._chStalePromptReloadBtn!.onclick = () => {
      this.hideStalePrompt();
      delete this._chCursors[name];
      this.persistCursors();
      this.openChannelEventSource(name);
      this.fullLoad(name);
    };
  }

  private selectChannel(name: string | null): void {
    if (this._chPollTimer) clearTimeout(this._chPollTimer);
    if (this._chEventSource) {
      this._chEventSource.close();
      this._chEventSource = null;
    }
    this.hideStalePrompt();

    // Re-read sessionStorage so external mutations (e.g. test code) are visible
    try {
      const s = sessionStorage.getItem(CURSOR_STORE_KEY);
      if (s) this._chCursors = JSON.parse(s);
    } catch (_e) {
      // ignore
    }

    const sameChannel = name === this._chSelectedName;
    this._chSelectedName = name || null;

    if (this._chError) this._chError.textContent = "";
    if (this._chSendBtn) this._chSendBtn.disabled = !name;
    this.updateTypeSelectForChannel(name || null);

    if (!name) {
      if (this._chFeed) this._chFeed.innerHTML = "";
      return;
    }

    const cursor = this._chCursors[name];
    if (cursor) {
      if (Date.now() - cursor.ts >= this._chStalenessMs) {
        // Stale: clear feed and let user decide
        if (this._chFeed) this._chFeed.innerHTML = "";
        this.showStalePrompt(name, cursor.id);
      } else if (sameChannel && this._chFeed && this._chFeed.children.length > 0) {
        // Same channel, panel just reopened: feed still rendered, just append new
        this.openChannelEventSource(name);
        this.catchUp(name, cursor.id);
      } else {
        // Different channel or empty feed: fresh history load
        if (this._chFeed) this._chFeed.innerHTML = "";
        this.openChannelEventSource(name);
        this.fullLoad(name);
      }
    } else {
      if (this._chFeed) this._chFeed.innerHTML = "";
      this.openChannelEventSource(name);
      this.fullLoad(name);
    }
  }

  private loadChannels(): void {
    fetch("/api/mesh/channels")
      .then((r) => r.json())
      .then((channels: ChannelInfo[]) => {
        const chSelect = this._chSelect;
        if (!chSelect) return;

        channels.sort((a, b) => a.name.localeCompare(b.name));
        this._chChannelAllowedTypes = {};
        chSelect.innerHTML = '<option value="">— select channel —</option>';

        channels.forEach((ch) => {
          this._chChannelAllowedTypes[ch.name] = ch.allowedTypes || null;
          const opt = document.createElement("option");
          opt.value = ch.name;
          opt.textContent = ch.name + (ch.message_count ? " (" + ch.message_count + ")" : "");
          chSelect.appendChild(opt);
        });

        // Priority 1: URL ?channel= preselect (opens panel if not already open)
        const params = new URLSearchParams(window.location.search);
        const preselect = this._preselect || params.get("channel");
        if (preselect) {
          chSelect.value = preselect;
          this.selectChannel(preselect);
          if (this.classList.contains("collapsed")) this.open();
          return;
        }
        // Priority 2: Case channel auto-select (panel already open)
        if (this._caseId) {
          this.selectCaseChannel(this._caseId);
        }
      })
      .catch(() => {
        // ignore
      });
  }

  private sendMessage(): void {
    const content = this._chInput ? this._chInput.value.trim() : "";
    const type = this._chTypeSelect ? this._chTypeSelect.value : "command";
    if (!content || !this._chSelectedName) return;

    if (this._chSendBtn) this._chSendBtn.disabled = true;
    if (this._chError) this._chError.textContent = "";

    fetch(
      "/api/mesh/channels/" +
        encodeURIComponent(this._chSelectedName) +
        "/messages",
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ content: content, type: type }),
      }
    )
      .then((r) => {
        if (!r.ok)
          return r.text().then((t) => {
            throw new Error(t || String(r.status));
          });
        if (this._chInput) this._chInput.value = "";
        if (this._chSendBtn) this._chSendBtn.disabled = true;
      })
      .catch((err: Error) => {
        if (this._chError) this._chError.textContent = err.message || "Send failed.";
        if (this._chSendBtn) this._chSendBtn.disabled = false;
      });
  }
}

customElements.define("claudony-channel-panel", ClaudonyChannelPanel);
