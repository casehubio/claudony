import { LitElement, html, css, nothing } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import type { QhorusMessage, QhorusChannel, MessageType } from '@casehubio/blocks-ui-channel-activity';
import '@casehubio/blocks-ui-channel-activity';
import { toQhorusMessage, toQhorusChannel } from '../util/channel-adapter';
import type { TimelineEntry, ChannelInfo } from '../util/channel-adapter';

interface ChannelPanelConfig {
  sessionId: string;
  caseId?: string;
  roleName?: string;
  createdAt?: string;
  channel?: string;
  status?: string;
}

interface WorkerSummary {
  workerId?: string;
  workerName?: string;
  startedAt?: string;
  completedAt?: string;
}

const POLL_MS = 3000;
const CURSOR_STORE_KEY = 'claudony.channel.cursors';

@customElement('claudony-channel-panel')
export class ClaudonyChannelPanel extends LitElement {
  @state() private _channels: QhorusChannel[] = [];
  @state() private _selectedChannelId = '';
  @state() private _messages: QhorusMessage[] = [];
  @state() private _collapsed = true;
  @state() private _error = '';

  @state() private _caseId: string | null = null;
  @state() private _roleName: string | null = null;
  @state() private _sessionStatus: string | null = null;
  @state() private _sessionCreatedAt: Date | null = null;
  @state() private _elapsed = '';
  @state() private _lineageWorkers: WorkerSummary[] = [];
  @state() private _lineageExpanded = false;
  @state() private _lineageLoaded = false;

  @state() private _showStalePrompt = false;
  @state() private _staleCursorId = 0;

  private _sessionId = '';
  private _preselect: string | null = null;
  private _stalenessMs = 30 * 60 * 1000;
  private _cursors: Record<string, { id: number; ts: number }> = {};
  private _channelAllowedTypes: Record<string, string | null> = {};

  private _eventSource: EventSource | null = null;
  private _pollTimer: ReturnType<typeof setTimeout> | null = null;
  private _lineagePollTimer: ReturnType<typeof setTimeout> | null = null;
  private _elapsedTicker: ReturnType<typeof setInterval> | null = null;

  static override styles = css`
    :host {
      width: 300px;
      min-width: 300px;
      display: flex;
      flex-direction: column;
      background: var(--pages-neutral-2, var(--surface, #252526));
      border-left: 1px solid var(--pages-neutral-4, var(--border, #3e3e42));
      overflow: hidden;
      transition: width 0.2s ease, min-width 0.2s ease;
      flex-shrink: 0;
    }
    :host(.collapsed) { width: 0; min-width: 0; }

    .header {
      display: flex; align-items: center; gap: 6px;
      padding: 8px 10px;
      border-bottom: 1px solid var(--pages-neutral-4, #3e3e42);
      flex-shrink: 0;
    }
    .ch-select {
      flex: 1;
      background: var(--pages-neutral-1, #1e1e1e);
      color: var(--pages-neutral-11, #ccc);
      border: 1px solid var(--pages-neutral-4, #3e3e42);
      border-radius: var(--pages-radius-sm, 4px);
      padding: 4px 6px; font-size: 12px;
    }
    .ch-select:focus { outline: none; border-color: var(--pages-accent-9, #007acc); }
    .close-btn {
      background: transparent; border: none;
      color: var(--pages-neutral-8, #888);
      padding: 3px 6px; font-size: 13px; cursor: pointer;
    }
    .close-btn:hover { color: var(--pages-neutral-11, #ccc); }

    .feed-container { flex: 1; overflow: hidden; display: flex; flex-direction: column; }

    .case-header {
      border-bottom: 1px solid var(--pages-neutral-4, #3e3e42);
      padding: 6px 10px 4px; flex-shrink: 0;
    }
    .case-info { display: flex; align-items: center; gap: 6px; margin-bottom: 4px; }
    .case-role {
      font-size: 12px; font-weight: 600; color: var(--pages-neutral-11, #ccc);
      flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    }
    .status-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
    .status-dot.active { background: var(--pages-success-9, #4ec9b0); }
    .status-dot.idle { background: var(--pages-neutral-8, #888); }
    .status-dot.waiting { background: var(--pages-warning-9, #dcdcaa); }
    .status-dot.faulted { background: var(--pages-danger-9, #f44747); }
    .case-elapsed { font-size: 11px; color: var(--pages-neutral-8, #888); flex-shrink: 0; }

    .lineage-toggle {
      display: flex; align-items: center; gap: 5px;
      cursor: pointer; padding: 2px 0; font-size: 11px;
      color: var(--pages-neutral-8, #888); user-select: none;
    }
    .lineage-toggle:hover { color: var(--pages-neutral-11, #ccc); }
    .chevron { font-size: 9px; transition: transform 0.15s ease; display: inline-block; }
    .chevron.expanded { transform: rotate(90deg); }

    .lineage {
      border-bottom: 1px solid var(--pages-neutral-4, #3e3e42);
      padding: 4px 10px; flex-shrink: 0;
    }
    .lineage-row {
      display: flex; align-items: center; gap: 6px;
      padding: 3px 0; font-size: 11px;
      border-bottom: 1px solid var(--pages-neutral-4, #3e3e42);
    }
    .lineage-row:last-child { border-bottom: none; }
    .lineage-name {
      color: var(--pages-success-9, #4ec9b0); font-weight: 600;
      flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    }
    .lineage-time {
      color: var(--pages-neutral-8, #888); flex-shrink: 0;
      font-family: Menlo, Monaco, 'Courier New', monospace;
    }
    .lineage-empty { font-size: 11px; color: var(--pages-neutral-8, #888); font-style: italic; padding: 2px 0; }

    .stale-prompt {
      display: flex; flex-direction: column; gap: 6px;
      padding: 10px 8px;
      background: var(--pages-warning-3, rgba(240,194,127,.07));
      border-bottom: 1px solid var(--pages-warning-11, rgba(240,194,127,.2));
      font-size: 12px;
    }
    .stale-msg { color: var(--pages-neutral-8, #888); font-style: italic; }
    .stale-btn {
      background: rgba(255,255,255,.08); border: 1px solid rgba(255,255,255,.15);
      color: var(--pages-neutral-11, #ccc); padding: 4px 8px; font-size: 11px;
      border-radius: 3px; cursor: pointer; text-align: left;
    }
    .stale-btn:hover { background: rgba(255,255,255,.14); }
    .stale-btn.secondary { opacity: 0.7; }

    .error { font-size: 11px; color: var(--pages-danger-9, #f44747); padding: 4px 8px; }
  `;

  connectedCallback(): void {
    super.connectedCallback();
    this._loadCursors();
    this._fetchMeshConfig();
  }

  disconnectedCallback(): void {
    super.disconnectedCallback();
    this.destroy();
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
    this._closeEventSource();
    if (this._pollTimer) { clearTimeout(this._pollTimer); this._pollTimer = null; }
    if (this._lineagePollTimer) { clearTimeout(this._lineagePollTimer); this._lineagePollTimer = null; }
    if (this._elapsedTicker) { clearInterval(this._elapsedTicker); this._elapsedTicker = null; }
  }

  open(): void {
    this._collapsed = false;
    this.classList.remove('collapsed');
    if (this._caseId && !this._lineageLoaded) {
      this._loadLineage();
      this._startElapsedTicker();
    }
    this._loadChannels();
  }

  close(): void {
    this._collapsed = true;
    this.classList.add('collapsed');
    this._showStalePrompt = false;
    this._closeEventSource();
    if (this._pollTimer) { clearTimeout(this._pollTimer); this._pollTimer = null; }
    if (this._lineagePollTimer) { clearTimeout(this._lineagePollTimer); this._lineagePollTimer = null; }
    if (this._elapsedTicker) { clearInterval(this._elapsedTicker); this._elapsedTicker = null; }
  }

  toggle(): void {
    if (this._collapsed) this.open(); else this.close();
  }

  override render() {
    const allowedTypes = this._selectedChannelId
      ? this._computeAllowedTypes(this._selectedChannelId) : undefined;

    return html`
      <div class="header">
        <select class="ch-select" @change=${(e: Event) => this._onChannelSelect(e)}>
          <option value="">— select channel —</option>
          ${this._channels.map(ch => html`
            <option value=${ch.id} ?selected=${ch.id === this._selectedChannelId}>${ch.name}</option>
          `)}
        </select>
        <button class="close-btn" @click=${() => this.close()} title="Close">&#10005;</button>
      </div>

      ${this._caseId ? this._renderCaseHeader() : nothing}
      ${this._lineageExpanded ? this._renderLineage() : nothing}

      ${this._showStalePrompt ? html`
        <div class="stale-prompt">
          <span class="stale-msg">You were away for a while.</span>
          <button class="stale-btn" @click=${() => this._onCatchUp()}>Catch up from where you left off</button>
          <button class="stale-btn secondary" @click=${() => this._onReload()}>Reload full history</button>
        </div>
      ` : nothing}

      <div class="feed-container">
        <channel-feed .messages=${this._messages} .channelId=${this._selectedChannelId}
          .staleCursorMinutes=${0}></channel-feed>
      </div>

      <channel-input .channelId=${this._selectedChannelId} .showTypeSelector=${true}
        .allowedTypes=${allowedTypes} @pages-event=${(e: CustomEvent) => this._onPagesEvent(e)}></channel-input>

      ${this._error ? html`<div class="error">${this._error}</div>` : nothing}
    `;
  }

  private _renderCaseHeader() {
    const role = this._roleName?.replace(/^claudony-worker-/, '') || '—';
    const status = (this._sessionStatus || 'idle').toLowerCase();
    return html`
      <div class="case-header">
        <div class="case-info">
          <span class="case-role">${role}</span>
          <span class="status-dot ${status}"></span>
          <span class="case-elapsed">${this._elapsed}</span>
        </div>
        <div class="lineage-toggle" @click=${() => this._toggleLineage()}>
          <span class="chevron ${this._lineageExpanded ? 'expanded' : ''}">▶</span>
          <span>${this._lineageLoaded
            ? `${this._lineageWorkers.length} prior worker${this._lineageWorkers.length === 1 ? '' : 's'}`
            : 'Loading…'}</span>
        </div>
      </div>
    `;
  }

  private _renderLineage() {
    if (this._lineageWorkers.length === 0) {
      return html`<div class="lineage"><div class="lineage-empty">No prior workers</div></div>`;
    }
    return html`<div class="lineage">
      ${this._lineageWorkers.map(w => {
        const name = (w.workerName || w.workerId || '?').replace(/^claudony-worker-/, '');
        const start = w.startedAt ? new Date(w.startedAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : '?';
        const end = w.completedAt ? new Date(w.completedAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : '?';
        const durMs = w.startedAt && w.completedAt ? new Date(w.completedAt).getTime() - new Date(w.startedAt).getTime() : 0;
        const dur = durMs > 0 ? `${Math.ceil(durMs / 60000)}m` : '?';
        return html`<div class="lineage-row">
          <span class="lineage-name">${name}</span>
          <span class="lineage-time">${start}→${end} (${dur})</span>
        </div>`;
      })}
    </div>`;
  }

  private _onChannelSelect(e: Event) {
    this._selectChannel((e.target as HTMLSelectElement).value || null);
  }

  private _onPagesEvent(e: CustomEvent) {
    const { topic, payload } = e.detail ?? {};
    if (topic === 'channel:send-message') this._sendMessage(payload.content, payload.speechAct);
  }

  private _onCatchUp() {
    this._showStalePrompt = false;
    if (this._selectedChannelId) {
      this._openEventSource(this._selectedChannelId);
      this._catchUp(this._selectedChannelId, this._staleCursorId);
    }
  }

  private _onReload() {
    this._showStalePrompt = false;
    if (this._selectedChannelId) {
      delete this._cursors[this._selectedChannelId];
      this._persistCursors();
      this._openEventSource(this._selectedChannelId);
      this._fullLoad(this._selectedChannelId);
    }
  }

  private _toggleLineage() { this._lineageExpanded = !this._lineageExpanded; }

  private _loadCursors() {
    try { const s = sessionStorage.getItem(CURSOR_STORE_KEY); if (s) this._cursors = JSON.parse(s); }
    catch { /* ignore */ }
  }

  private _persistCursors() {
    try { sessionStorage.setItem(CURSOR_STORE_KEY, JSON.stringify(this._cursors)); }
    catch { /* ignore */ }
  }

  private _fetchMeshConfig() {
    fetch('/api/mesh/config').then(r => r.json())
      .then((cfg: { cursorStalenessMinutes?: number }) => {
        if (cfg?.cursorStalenessMinutes != null) this._stalenessMs = cfg.cursorStalenessMinutes * 60 * 1000;
      }).catch(() => { /* ignore */ });
  }

  private _loadChannels() {
    fetch('/api/mesh/channels').then(r => r.json())
      .then((channels: ChannelInfo[]) => {
        channels.sort((a, b) => a.name.localeCompare(b.name));
        this._channelAllowedTypes = {};
        channels.forEach(ch => { this._channelAllowedTypes[ch.name] = ch.allowedTypes || null; });
        this._channels = channels.map(toQhorusChannel);
        const preselect = this._preselect || new URLSearchParams(window.location.search).get('channel');
        if (preselect) { this._selectChannel(preselect); if (this._collapsed) this.open(); return; }
        if (this._caseId) this._selectCaseChannel(this._caseId);
      }).catch(() => { /* ignore */ });
  }

  private _selectCaseChannel(caseId: string) {
    const prefix = `case-${caseId}/`;
    const target = this._channels.find(ch => ch.name === `${prefix}work`)
      || this._channels.find(ch => ch.name.startsWith(prefix));
    if (target) this._selectChannel(target.id);
  }

  private _selectChannel(name: string | null) {
    this._closeEventSource();
    if (this._pollTimer) { clearTimeout(this._pollTimer); this._pollTimer = null; }
    this._showStalePrompt = false;
    this._error = '';
    this._loadCursors();
    this._selectedChannelId = name || '';
    if (!name) { this._messages = []; return; }

    const cursor = this._cursors[name];
    if (cursor && Date.now() - cursor.ts >= this._stalenessMs) {
      this._messages = [];
      this._staleCursorId = cursor.id;
      this._showStalePrompt = true;
    } else {
      this._messages = [];
      this._openEventSource(name);
      this._fullLoad(name);
    }
  }

  private _openEventSource(name: string) {
    this._closeEventSource();
    const afterId = this._cursors[name]?.id ?? 0;
    this._eventSource = new EventSource(`/api/mesh/channels/${encodeURIComponent(name)}/events?after=${afterId}`);
    this._eventSource.onmessage = (e: MessageEvent) => {
      try {
        const entries = JSON.parse(e.data) as TimelineEntry[];
        if (Array.isArray(entries) && entries.length) this._appendMessages(name, entries);
      } catch { /* ignore */ }
    };
    this._eventSource.onerror = () => {
      this._closeEventSource();
      if (this._selectedChannelId) this._pollTimer = setTimeout(() => this._pollChannel(), POLL_MS);
    };
  }

  private _closeEventSource() {
    if (this._eventSource) { this._eventSource.close(); this._eventSource = null; }
  }

  private _pollChannel() {
    if (!this._selectedChannelId) return;
    const lastId = this._cursors[this._selectedChannelId]?.id ?? 0;
    fetch(`/api/mesh/channels/${encodeURIComponent(this._selectedChannelId)}/timeline?limit=50${lastId ? `&after=${lastId}` : ''}`)
      .then(r => r.ok ? r.json() : undefined)
      .then((e: TimelineEntry[] | undefined) => { if (e?.length) this._appendMessages(this._selectedChannelId, e); })
      .catch(() => { /* ignore */ });
    this._pollTimer = setTimeout(() => this._pollChannel(), POLL_MS);
  }

  private _catchUp(name: string, fromId: number) {
    fetch(`/api/mesh/channels/${encodeURIComponent(name)}/timeline?limit=50&after=${fromId}`)
      .then(r => r.ok ? r.json() : undefined)
      .then((e: TimelineEntry[] | undefined) => { if (e?.length) this._appendMessages(name, e); })
      .catch(() => { this._error = 'Catch-up failed — some messages may be missing.'; });
  }

  private _fullLoad(name: string) {
    fetch(`/api/mesh/channels/${encodeURIComponent(name)}/timeline?limit=100`)
      .then(r => r.ok ? r.json() : undefined)
      .then((e: TimelineEntry[] | undefined) => { if (e?.length) this._appendMessages(name, e); })
      .catch(() => { this._error = 'Failed to load messages.'; });
  }

  private _appendMessages(channelName: string, entries: TimelineEntry[]) {
    const existingIds = new Set(this._messages.map(m => m.id));
    const newMsgs: QhorusMessage[] = [];
    let cursorAdvanced = false;
    for (const entry of entries) {
      if (existingIds.has(String(entry.id ?? 0))) continue;
      newMsgs.push(toQhorusMessage(entry));
      if (entry.id && channelName) {
        const c = this._cursors[channelName];
        if (!c || entry.id > c.id) { this._cursors[channelName] = { id: entry.id, ts: Date.now() }; cursorAdvanced = true; }
      }
    }
    if (newMsgs.length > 0) this._messages = [...this._messages, ...newMsgs];
    if (cursorAdvanced) this._persistCursors();
  }

  private _sendMessage(content: string, speechAct?: string) {
    if (!content?.trim() || !this._selectedChannelId) return;
    this._error = '';
    fetch(`/api/mesh/channels/${encodeURIComponent(this._selectedChannelId)}/messages`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content: content.trim(), type: speechAct || 'command' }),
    })
      .then(r => { if (!r.ok) return r.text().then(t => { throw new Error(t || String(r.status)); }); })
      .catch((err: Error) => { this._error = err.message || 'Send failed.'; });
  }

  private _loadLineage() {
    fetch(`/api/sessions/${this._sessionId}/lineage`)
      .then(r => r.ok ? r.json() : [])
      .then((w: WorkerSummary[]) => { this._lineageWorkers = w; this._lineageLoaded = true; })
      .catch(() => { this._lineageWorkers = []; this._lineageLoaded = true; });
    if (this._lineagePollTimer) clearTimeout(this._lineagePollTimer);
    this._lineagePollTimer = setTimeout(() => this._loadLineage(), 60000);
  }

  private _startElapsedTicker() {
    this._updateElapsed();
    if (this._elapsedTicker) clearInterval(this._elapsedTicker);
    this._elapsedTicker = setInterval(() => this._updateElapsed(), 30000);
  }

  private _updateElapsed() {
    if (!this._sessionCreatedAt) { this._elapsed = '—'; return; }
    const diffM = Math.floor((Date.now() - this._sessionCreatedAt.getTime()) / 60000);
    if (diffM < 1) this._elapsed = '<1m';
    else if (diffM < 60) this._elapsed = `${diffM}m`;
    else this._elapsed = `${Math.floor(diffM / 60)}h ${diffM % 60}m`;
  }

  private _computeAllowedTypes(channelName: string): MessageType[] | undefined {
    const raw = this._channelAllowedTypes[channelName];
    return raw ? raw.split(',').map(t => t.trim().toUpperCase() as MessageType) : undefined;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'claudony-channel-panel': ClaudonyChannelPanel;
  }
}
