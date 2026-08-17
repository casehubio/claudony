import { LitElement, html, css, nothing } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import {
  ChannelEventTopics,
  PushController, ALL_TOPICS,
  ChannelStateController,
  MessagingController,
  MembershipController,
  ReactionController,
  CommitmentController,
} from '@casehubio/blocks-ui-channel-activity';
import type { SendMessagePayload, ArtefactRef, MessageType } from '@casehubio/blocks-ui-channel-activity';
import '@casehubio/blocks-ui-channel-activity';
import '@casehubio/pages-ui-components';
import { createEventConnection } from '@casehubio/pages-data/dataset/external/sources/event-connection.js';
import type { EventConnection } from '@casehubio/pages-data/dataset/external/sources/event-connection.js';
import { attachTerminal, type TerminalHandle } from '../util/terminal-controller.js';
import { fetchWithAuth } from '../util/auth.js';

interface WorkbenchConfig {
  sessionId: string;
  sessionName: string;
  proxyPeer?: string;
  caseId?: string;
  roleName?: string;
  status?: string;
  createdAt?: string;
  channel?: string;
}

interface WorkerSummary {
  workerId?: string;
  workerName?: string;
  startedAt?: string;
  completedAt?: string;
}

interface WorkerInfo {
  id: string;
  name: string;
  status: string;
  roleName?: string;
  createdAt: string;
}

@customElement('claudony-workbench')
export class ClaudonyWorkbench extends LitElement {
  // ── Controllers ─────────────────────────────────────────────────────────
  private _push = new PushController(this);
  private _channels = new ChannelStateController(this, this._push);
  private _messaging = new MessagingController(this, this._channels, {
    restBase: '/api',
    fetch: fetchWithAuth,
  });
  private _members = new MembershipController(this, this._push, this._channels);
  private _reactions = new ReactionController(this, this._push, this._channels, {
    restBase: '/api',
    fetch: fetchWithAuth,
  });
  private _commitments = new CommitmentController(this, this._push, this._channels);

  // ── Case context ─────────────────────────────────────────────────────────
  @state() private _caseId: string | null = null;
  @state() private _roleName: string | null = null;
  @state() private _sessionStatus: string | null = null;
  @state() private _sessionCreatedAt: Date | null = null;
  @state() private _elapsed = '';
  @state() private _lineageWorkers: WorkerSummary[] = [];
  @state() private _lineageExpanded = false;
  @state() private _lineageLoaded = false;

  // ── Workers ──────────────────────────────────────────────────────────────
  @state() private _workers: WorkerInfo[] = [];

  // ── App-specific state ──────────────────────────────────────────────────
  @state() private _selectedArtefactRef?: ArtefactRef;
  @state() private _error = '';
  @state() private _dockState: Record<string, boolean> = { tasks: false, correlation: false, artifacts: false, members: false };

  // ── Non-reactive state ───────────────────────────────────────────────────
  private _sessionId = '';
  private _sessionName = '';
  private _proxyPeer?: string;
  private _preselect: string | null = null;

  private _handle: TerminalHandle | null = null;
  private _eventConn?: EventConnection;
  private _lineagePollTimer: ReturnType<typeof setTimeout> | null = null;
  private _elapsedTicker: ReturnType<typeof setInterval> | null = null;
  private _workerEventSource: EventSource | null = null;

  static override styles = css`
    :host {
      display: flex;
      flex: 1;
      overflow: hidden;
      font-family: var(--pages-font-family, 'Inter', system-ui, sans-serif);
    }

    .nav-panel {
      width: 220px;
      min-width: 220px;
      display: flex;
      flex-direction: column;
      background: var(--pages-neutral-2, #252526);
      border-right: 1px solid var(--pages-neutral-4, #3e3e42);
      overflow: hidden;
    }

    .main-panel {
      flex: 1;
      display: flex;
      flex-direction: column;
      overflow: hidden;
    }

    .terminal-area {
      flex: 1;
      overflow: hidden;
      display: flex;
    }
    .terminal-area pages-component-terminal {
      flex: 1;
      overflow: hidden;
    }
    pages-component-terminal .xterm { height: 100%; }
    pages-component-terminal .xterm-viewport { overflow: hidden !important; }

    .conversation-area {
      width: 360px;
      min-width: 360px;
      display: flex;
      flex-direction: column;
      background: var(--pages-neutral-2, #252526);
      border-left: 1px solid var(--pages-neutral-4, #3e3e42);
      overflow: hidden;
    }

    .feed-container { flex: 1; overflow: hidden; display: flex; flex-direction: column; }

    .dock-strip {
      display: flex;
      gap: 2px;
      padding: 4px 8px;
      border-top: 1px solid var(--pages-neutral-4, #3e3e42);
      background: var(--pages-neutral-2, #252526);
      flex-shrink: 0;
    }

    .context-panel {
      width: 280px;
      min-width: 280px;
      display: flex;
      flex-direction: column;
      background: var(--pages-neutral-2, #252526);
      border-left: 1px solid var(--pages-neutral-4, #3e3e42);
      overflow: hidden;
    }

    .case-header {
      border-bottom: 1px solid var(--pages-neutral-4, #3e3e42);
      padding: 6px 10px 4px;
      flex-shrink: 0;
    }
    .case-info { display: flex; align-items: center; gap: 6px; margin-bottom: 4px; }
    .case-role {
      font-size: var(--pages-font-size-base); font-weight: 600; color: var(--pages-neutral-11, #ccc);
      flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    }
    .case-elapsed { font-size: var(--pages-font-size-sm); color: var(--pages-neutral-8, #888); flex-shrink: 0; }

    .lineage-toggle {
      display: flex; align-items: center; gap: 5px;
      cursor: pointer; padding: 2px 0; font-size: var(--pages-font-size-sm);
      color: var(--pages-neutral-8, #888); user-select: none;
    }
    .lineage-toggle:hover { color: var(--pages-neutral-11, #ccc); }
    .chevron { font-size: var(--pages-font-size-xs); transition: transform 0.15s ease; display: inline-block; }
    .chevron.expanded { transform: rotate(90deg); }

    .lineage {
      border-bottom: 1px solid var(--pages-neutral-4, #3e3e42);
      padding: 4px 10px; flex-shrink: 0;
    }
    .lineage-row {
      display: flex; align-items: center; gap: 6px;
      padding: 3px 0; font-size: var(--pages-font-size-sm);
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
    .lineage-empty { font-size: var(--pages-font-size-sm); color: var(--pages-neutral-8, #888); font-style: italic; padding: 2px 0; }

    .error { font-size: var(--pages-font-size-sm); color: var(--pages-danger-9, #f44747); padding: 4px 8px; }

    .worker-list { overflow-y: auto; padding: 4px 0; }
    .worker-row {
      display: flex; align-items: center; gap: 8px;
      padding: 6px 12px; cursor: pointer; font-size: var(--pages-font-size-base);
    }
    .worker-row:hover { background: rgba(255,255,255,0.04); }
    .worker-row.active-worker { background: rgba(0,122,204,0.12); border-left: 2px solid var(--pages-accent-9, #007acc); }
    .worker-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .worker-time { font-size: var(--pages-font-size-sm); color: var(--pages-neutral-8, #888); }

    .section-title {
      font-size: var(--pages-font-size-sm); font-weight: 600; text-transform: uppercase;
      color: var(--pages-neutral-8, #888);
      padding: 8px 12px; border-bottom: 1px solid var(--pages-neutral-4, #3e3e42);
    }
  `;

  // ── Lifecycle ────────────────────────────────────────────────────────────

  override connectedCallback(): void {
    super.connectedCallback();
    this.addEventListener('pages-event', this._onPagesEvent as EventListener);
  }

  override firstUpdated(): void {
    const container = this.renderRoot.querySelector('#terminal-container') as HTMLElement;
    if (container && this._sessionId) {
      this._handle = attachTerminal(container, this._sessionId, { proxyPeer: this._proxyPeer });
      if ((window as unknown as Record<string, unknown>).__CLAUDONY_TEST_MODE__ && this._handle.getTerminal()) {
        (window as unknown as Record<string, unknown>)._xtermTerminal = this._handle.getTerminal()!.terminal;
      }
    }
    this._connectPush();
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.removeEventListener('pages-event', this._onPagesEvent as EventListener);
    this._destroy();
  }

  configure(opts: WorkbenchConfig): void {
    this._sessionId = opts.sessionId;
    this._sessionName = opts.sessionName;
    this._proxyPeer = opts.proxyPeer;
    this._caseId = opts.caseId || null;
    this._roleName = opts.roleName || null;
    this._sessionCreatedAt = opts.createdAt ? new Date(opts.createdAt) : null;
    this._preselect = opts.channel || null;
    this._sessionStatus = opts.status || null;

    if (this._caseId) {
      this._loadLineage();
      this._startElapsedTicker();
      this._connectWorkerSSE();
    }
  }

  private _connectPush(): void {
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const url = `${proto}//${location.host}/ws/push`;
    const eventTarget = new EventTarget();

    this._eventConn = createEventConnection(url, {
      config: { eventTarget },
      onStatusChange: (status) => { this._push.setConnectionStatus(status as any); },
    });

    this._eventConn.listen(ALL_TOPICS);

    eventTarget.addEventListener('pages-event', (e: Event) => {
      const detail = (e as CustomEvent).detail;
      if (detail?.payload) {
        this._push.applyOp(detail.payload as any);
      }
    });

    if (this._preselect) {
      const check = () => {
        if (this._channels.channels.length > 0) {
          const target = this._channels.channels.find(ch => ch.name === this._preselect || ch.id === this._preselect);
          if (target) this._channels.selectedChannelId = target.id;
        } else {
          setTimeout(check, 200);
        }
      };
      setTimeout(check, 200);
    }
  }

  private _destroy(): void {
    this._handle?.dispose();
    this._eventConn?.close();
    if (this._lineagePollTimer) { clearTimeout(this._lineagePollTimer); this._lineagePollTimer = null; }
    if (this._elapsedTicker) { clearInterval(this._elapsedTicker); this._elapsedTicker = null; }
    if (this._workerEventSource) { this._workerEventSource.close(); this._workerEventSource = null; }
  }

  // ── Event routing ────────────────────────────────────────────────────────

  private _onPagesEvent = (e: CustomEvent): void => {
    const { topic, payload } = e.detail ?? {};

    if (topic === ChannelEventTopics.SEND_MESSAGE) {
      this._sendMessage(payload as SendMessagePayload);
    } else {
      this._channels.handleEvent(topic, payload);
      this._messaging.handleEvent(topic, payload);
      this._reactions.handleEvent(topic, payload);
      this._commitments.handleEvent(topic, payload);
    }

    if (topic === 'channel:artefact-selected') {
      this._selectedArtefactRef = (payload as { artefactRef: ArtefactRef }).artefactRef;
      if (!this._dockState['artifacts']) {
        this._dockState = { ...this._dockState, artifacts: true };
      }
    }
    if (topic === 'terminal-resize') {
      this._handle?.resize(payload.cols, payload.rows);
    }
    if (topic === 'key-pressed') {
      this._handle?.sendInput(payload.code);
    }
    if (topic === 'worker-selected') {
      this._handleWorkerSwitch(payload.sessionId, payload.name);
    }
  };

  private async _sendMessage(payload: SendMessagePayload): Promise<void> {
    this._error = '';
    const channelName = this._channels.selectedChannelId;
    if (!payload.content?.trim() || !channelName) return;

    const body: Record<string, unknown> = {
      content: payload.content.trim(),
      type: payload.speechAct || 'command',
    };
    if (payload.inReplyTo) {
      body.inReplyTo = Number(payload.inReplyTo);
      const replyMsg = this._channels.filteredMessages().find(m => m.id === payload.inReplyTo);
      if (replyMsg?.correlationId) body.correlationId = replyMsg.correlationId;
    }
    if (payload.topic) body.topic = payload.topic;

    try {
      const res = await fetchWithAuth(`/api/mesh/channels/${encodeURIComponent(channelName)}/messages`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
      if (!res.ok) {
        const text = await res.text();
        throw new Error(text || String(res.status));
      }
      this._messaging.replyTo = undefined;
      this.requestUpdate();
    } catch (err) {
      this._error = (err as Error).message || 'Send failed.';
    }
  }

  // ── Case context ─────────────────────────────────────────────────────────

  private _loadLineage(): void {
    fetch(`/api/sessions/${this._sessionId}/lineage`)
      .then(r => r.ok ? r.json() : [])
      .then((w: WorkerSummary[]) => { this._lineageWorkers = w; this._lineageLoaded = true; })
      .catch(() => { this._lineageWorkers = []; this._lineageLoaded = true; });
    if (this._lineagePollTimer) clearTimeout(this._lineagePollTimer);
    this._lineagePollTimer = setTimeout(() => this._loadLineage(), 60000);
  }

  private _startElapsedTicker(): void {
    this._updateElapsed();
    if (this._elapsedTicker) clearInterval(this._elapsedTicker);
    this._elapsedTicker = setInterval(() => this._updateElapsed(), 30000);
  }

  private _updateElapsed(): void {
    if (!this._sessionCreatedAt) { this._elapsed = '\u{2014}'; return; }
    const diffM = Math.floor((Date.now() - this._sessionCreatedAt.getTime()) / 60000);
    if (diffM < 1) this._elapsed = '<1m';
    else if (diffM < 60) this._elapsed = `${diffM}m`;
    else this._elapsed = `${Math.floor(diffM / 60)}h ${diffM % 60}m`;
  }

  // ── Workers ──────────────────────────────────────────────────────────────

  private _connectWorkerSSE(): void {
    if (this._workerEventSource) { this._workerEventSource.close(); }
    this._workerEventSource = new EventSource(`/api/sessions/${this._sessionId}/case-events`);
    this._workerEventSource.onmessage = (e) => {
      this._workers = JSON.parse(e.data) as WorkerInfo[];
    };
  }

  private _handleWorkerSwitch(newSessionId: string, newName: string): void {
    this._sessionId = newSessionId;
    this._sessionName = newName;

    history.replaceState(null, '', '?id=' + newSessionId + '&name=' + encodeURIComponent(newName));

    this._handle?.switchSession(newSessionId, { proxyPeer: this._proxyPeer });
    this._connectWorkerSSE();

    this._channels.selectedChannelId = '';
    this._selectedArtefactRef = undefined;

    this.dispatchEvent(new CustomEvent('pages-event', {
      bubbles: true, composed: true,
      detail: { topic: 'session-changed', payload: { sessionName: newName, sessionId: newSessionId } },
    }));

    if ((window as unknown as Record<string, unknown>).__CLAUDONY_TEST_MODE__ && this._handle?.getTerminal()) {
      (window as unknown as Record<string, unknown>)._xtermTerminal = this._handle.getTerminal()!.terminal;
    }
  }

  // ── Dock ─────────────────────────────────────────────────────────────────

  private _toggleDock(panelId: string): void {
    this._dockState = { ...this._dockState, [panelId]: !this._dockState[panelId] };
  }

  // ── Render ───────────────────────────────────────────────────────────────

  override render() {
    const messages = this._channels.filteredMessages();
    const topics = this._channels.channelTopics();
    const members = this._members.filteredMembers();
    const reactions = this._reactions.filteredReactions();
    const commitments = this._commitments.commitments;
    const activeContextPanel = Object.entries(this._dockState).find(([, v]) => v)?.[0];

    return html`
      ${this._workers.length > 0 ? this._renderWorkerNav() : nothing}

      <div class="main-panel">
        <div class="terminal-area" id="terminal-container"></div>
      </div>

      <div class="conversation-area">
        ${this._caseId ? this._renderCaseHeader() : nothing}
        ${this._lineageExpanded ? this._renderLineage() : nothing}

        <channel-nav .channels=${this._channels.channels}
          .selectedChannelId=${this._channels.selectedChannelId}></channel-nav>

        ${topics.length > 0 ? html`
          <blocks-channel-topic-bar .topics=${topics}
            .viewMode=${this._channels.viewMode}></blocks-channel-topic-bar>
        ` : nothing}

        <div class="feed-container">
          <channel-feed .messages=${messages}
            .channelId=${this._channels.selectedChannelId}
            .reactions=${reactions}
            .staleCursorMinutes=${0}></channel-feed>
        </div>

        <channel-input .channelId=${this._channels.selectedChannelId}
          .showTypeSelector=${true}
          .replyTo=${this._messaging.replyTo}></channel-input>

        ${this._error ? html`<div class="error">${this._error}</div>` : nothing}

        <div class="dock-strip">
          <pages-button size="xs" variant=${this._dockState['tasks'] ? 'secondary' : 'ghost'} label="Tasks"
            @click=${() => this._toggleDock('tasks')}></pages-button>
          <pages-button size="xs" variant=${this._dockState['correlation'] ? 'secondary' : 'ghost'} label="Correlation"
            @click=${() => this._toggleDock('correlation')}></pages-button>
          <pages-button size="xs" variant=${this._dockState['artifacts'] ? 'secondary' : 'ghost'} label="Artifacts"
            @click=${() => this._toggleDock('artifacts')}></pages-button>
          <pages-button size="xs" variant=${this._dockState['members'] ? 'secondary' : 'ghost'} label="Members"
            @click=${() => this._toggleDock('members')}></pages-button>
        </div>
      </div>

      ${activeContextPanel ? html`
        <div class="context-panel">
          ${this._renderContextPanel(activeContextPanel, messages, commitments)}
        </div>
      ` : nothing}
    `;
  }

  private _renderContextPanel(panelId: string, messages: any[], commitments: any) {
    switch (panelId) {
      case 'tasks':
        return html`<channel-task-panel
          .messages=${messages}
          .commitments=${commitments}
          .selectedMessageId=${this._commitments.selectedMessageId}></channel-task-panel>`;
      case 'correlation':
        return html`<channel-correlation-panel
          .messages=${messages}
          .commitments=${commitments}
          .selectedMessageId=${this._commitments.selectedMessageId}></channel-correlation-panel>`;
      case 'artifacts':
        return html`<channel-artifact-panel
          .selectedArtefactRef=${this._selectedArtefactRef}></channel-artifact-panel>`;
      case 'members':
        return html`<blocks-channel-member-panel
          .members=${this._members.filteredMembers()}
          .presence=${this._members.presence}></blocks-channel-member-panel>`;
      default:
        return nothing;
    }
  }

  private _renderCaseHeader() {
    const role = this._roleName?.replace(/^claudony-worker-/, '') || '\u{2014}';
    const status = (this._sessionStatus || 'idle').toLowerCase();
    return html`
      <div class="case-header">
        <div class="case-info">
          <span class="case-role">${role}</span>
          <pages-status-dot variant=${status === 'active' ? 'success' : status === 'waiting' ? 'warning' : status === 'faulted' ? 'danger' : 'neutral'}></pages-status-dot>
          <span class="case-elapsed">${this._elapsed}</span>
        </div>
        <div class="lineage-toggle" @click=${() => { this._lineageExpanded = !this._lineageExpanded; }}>
          <span class="chevron ${this._lineageExpanded ? 'expanded' : ''}">\u{25B6}</span>
          <span>${this._lineageLoaded
            ? `${this._lineageWorkers.length} prior worker${this._lineageWorkers.length === 1 ? '' : 's'}`
            : 'Loading\u{2026}'}</span>
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
          <span class="lineage-time">${start}\u{2192}${end} (${dur})</span>
        </div>`;
      })}
    </div>`;
  }

  private _renderWorkerNav() {
    return html`
      <div class="nav-panel">
        <div class="section-title">Workers</div>
        <div class="worker-list">
          ${this._workers.map(w => {
            const status = (w.status || 'idle').toLowerCase();
            const isActive = w.id === this._sessionId;
            const displayName = w.roleName || w.name.replace(/^claudony-worker-/, '').replace(/^claudony-/, '');
            const diffMs = Date.now() - new Date(w.createdAt).getTime();
            const m = Math.floor(diffMs / 60000);
            const timeAgo = m < 1 ? 'now' : m < 60 ? `${m}m` : `${Math.floor(m / 60)}h`;
            return html`
              <div class="worker-row ${isActive ? 'active-worker' : ''}"
                @click=${() => { if (!isActive) this._handleWorkerSwitch(w.id, displayName); }}>
                <pages-status-dot variant=${status === 'active' ? 'success' : status === 'waiting' ? 'warning' : status === 'faulted' ? 'danger' : 'neutral'}></pages-status-dot>
                <span class="worker-name">${displayName}</span>
                <span class="worker-time">${timeAgo}</span>
              </div>`;
          })}
        </div>
      </div>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'claudony-workbench': ClaudonyWorkbench;
  }
}
