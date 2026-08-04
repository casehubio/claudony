import { LitElement, html, css, nothing, type PropertyValues } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import type { QhorusMessage, QhorusChannel, QhorusTopic, ChannelMember, Reaction, MessageType, ArtefactRef } from '@casehubio/blocks-ui-channel-activity';
import { ChannelEventTopics } from '@casehubio/blocks-ui-channel-activity';
import '@casehubio/blocks-ui-channel-activity';
import '@casehubio/pages-ui-components';
import { attachTerminal, type TerminalHandle } from '../util/terminal-controller.js';
import { toQhorusMessage, toQhorusChannel, toChannelMember, toQhorusTopic, type TimelineEntry, type ChannelInfo, type MembershipResponse, type TopicSummaryResponse } from '../util/channel-adapter.js';
import { toCommitmentMap, type CommitmentRecord } from '@casehubio/blocks-ui-channel-activity';
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

const POLL_MS = 3000;
const CURSOR_STORE_KEY = 'claudony.channel.cursors';

@customElement('claudony-workbench')
export class ClaudonyWorkbench extends LitElement {
  // ── Channel + message state ──────────────────────────────────────────────
  @state() private _channels: QhorusChannel[] = [];
  @state() private _messages: QhorusMessage[] = [];
  @state() private _commitments: Map<string, CommitmentRecord> = new Map();
  @state() private _selectedChannelId = '';
  @state() private _selectedMessageId?: string;
  @state() private _replyTo?: { messageId: string; senderName: string };
  @state() private _selectedArtefactRef?: ArtefactRef;
  @state() private _error = '';

  // ── Case context ─────────────────────────────────────────────────────────
  @state() private _caseId: string | null = null;
  @state() private _roleName: string | null = null;
  @state() private _sessionStatus: string | null = null;
  @state() private _sessionCreatedAt: Date | null = null;
  @state() private _elapsed = '';
  @state() private _lineageWorkers: WorkerSummary[] = [];
  @state() private _lineageExpanded = false;
  @state() private _lineageLoaded = false;

  // ── Stale cursor ─────────────────────────────────────────────────────────
  @state() private _showStalePrompt = false;
  @state() private _staleCursorId = 0;

  // ── Workers ──────────────────────────────────────────────────────────────
  @state() private _workers: WorkerInfo[] = [];

  // ── Dock panels ──────────────────────────────────────────────────────────
  @state() private _dockState: Record<string, boolean> = { tasks: false, correlation: false, artifacts: false, members: false };

  // ── Phase 4: reactions, topics, members, threads ────────────────────────
  @state() private _reactions: Reaction[] = [];
  @state() private _topics: QhorusTopic[] = [];
  @state() private _members: ChannelMember[] = [];
  @state() private _memberPresence: import('@casehubio/blocks-ui-channel-activity').PresenceState[] = [];
  @state() private _viewMode: 'flat' | 'threaded' = 'flat';

  // ── Non-reactive state ───────────────────────────────────────────────────
  private _sessionId = '';
  private _sessionName = '';
  private _proxyPeer?: string;
  private _preselect: string | null = null;
  private _stalenessMs = 30 * 60 * 1000;
  private _cursors: Record<string, { id: number; ts: number }> = {};
  private _channelAllowedTypes: Record<string, string | null> = {};

  private _handle: TerminalHandle | null = null;
  private _eventSource: EventSource | null = null;
  private _pollTimer: ReturnType<typeof setTimeout> | null = null;
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
    .dock-btn {
      background: none;
      border: 1px solid transparent;
      color: var(--pages-neutral-8, #888);
      padding: 3px 8px;
      font-size: var(--pages-font-size-sm);
      cursor: pointer;
      border-radius: var(--pages-radius-sm, 4px);
    }
    .dock-btn:hover { color: var(--pages-neutral-11, #ccc); background: rgba(255,255,255,0.05); }
    .dock-btn.active { color: var(--pages-accent-9, #6366f1); border-color: var(--pages-accent-9, #6366f1); }

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
    .status-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
    .status-dot.active { background: var(--pages-success-9, #4ec9b0); }
    .status-dot.idle { background: var(--pages-neutral-8, #888); }
    .status-dot.waiting { background: var(--pages-warning-9, #dcdcaa); }
    .status-dot.faulted { background: var(--pages-danger-9, #f44747); }
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

    .stale-prompt {
      display: flex; flex-direction: column; gap: 6px;
      padding: 10px 8px;
      background: var(--pages-warning-3, rgba(240,194,127,.07));
      border-bottom: 1px solid var(--pages-warning-11, rgba(240,194,127,.2));
      font-size: var(--pages-font-size-base);
    }
    .stale-msg { color: var(--pages-neutral-8, #888); font-style: italic; }
    .stale-btn {
      background: rgba(255,255,255,.08); border: 1px solid rgba(255,255,255,.15);
      color: var(--pages-neutral-11, #ccc); padding: 4px 8px; font-size: var(--pages-font-size-sm);
      border-radius: 3px; cursor: pointer; text-align: left;
    }
    .stale-btn:hover { background: rgba(255,255,255,.14); }
    .stale-btn.secondary { opacity: 0.7; }

    .error { font-size: var(--pages-font-size-sm); color: var(--pages-danger-9, #f44747); padding: 4px 8px; }

    .worker-list {
      overflow-y: auto;
      padding: 4px 0;
    }
    .worker-row {
      display: flex; align-items: center; gap: 8px;
      padding: 6px 12px; cursor: pointer; font-size: var(--pages-font-size-base);
    }
    .worker-row:hover { background: rgba(255,255,255,0.04); }
    .worker-row.active-worker { background: rgba(0,122,204,0.12); border-left: 2px solid var(--pages-accent-9, #007acc); }
    .worker-status-dot {
      width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0;
    }
    .worker-status-dot.active { background: var(--pages-success-9, #4ec9b0); }
    .worker-status-dot.idle { background: var(--pages-neutral-8, #888); }
    .worker-status-dot.waiting { background: var(--pages-warning-9, #dcdcaa); }
    .worker-status-dot.faulted { background: var(--pages-danger-9, #f44747); }
    .worker-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .worker-time { font-size: var(--pages-font-size-sm); color: var(--pages-neutral-8, #888); }

    .section-title {
      font-size: var(--pages-font-size-sm); font-weight: 600; text-transform: uppercase;
      color: var(--pages-neutral-8, #888);
      padding: 8px 12px; border-bottom: 1px solid var(--pages-neutral-4, #3e3e42);
    }
  `;

  // ── Lifecycle ────────────────────────────────────────────────────────────

  connectedCallback(): void {
    super.connectedCallback();
    this._loadCursors();
    this._fetchMeshConfig();
  }

  override firstUpdated(): void {
    const container = this.renderRoot.querySelector('#terminal-container') as HTMLElement;
    if (container && this._sessionId) {
      this._handle = attachTerminal(container, this._sessionId, { proxyPeer: this._proxyPeer });
      if ((window as unknown as Record<string, unknown>).__CLAUDONY_TEST_MODE__ && this._handle.getTerminal()) {
        (window as unknown as Record<string, unknown>)._xtermTerminal = this._handle.getTerminal()!.terminal;
      }
    }
    this.addEventListener('pages-event', ((e: CustomEvent) => this._onPagesEvent(e)) as EventListener);
  }

  disconnectedCallback(): void {
    super.disconnectedCallback();
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

    this._loadChannels();
    if (this._caseId) {
      this._loadLineage();
      this._startElapsedTicker();
      this._connectWorkerSSE();
    }
  }

  private _destroy(): void {
    this._handle?.dispose();
    this._closeEventSource();
    if (this._pollTimer) { clearTimeout(this._pollTimer); this._pollTimer = null; }
    if (this._lineagePollTimer) { clearTimeout(this._lineagePollTimer); this._lineagePollTimer = null; }
    if (this._elapsedTicker) { clearInterval(this._elapsedTicker); this._elapsedTicker = null; }
    if (this._workerEventSource) { this._workerEventSource.close(); this._workerEventSource = null; }
  }

  // ── Event routing ────────────────────────────────────────────────────────

  private _onPagesEvent(e: CustomEvent): void {
    const { topic, payload } = e.detail ?? {};
    switch (topic) {
      case ChannelEventTopics.SEND_MESSAGE:
        this._sendMessage(payload.content, payload.speechAct, payload.inReplyTo);
        break;
      case ChannelEventTopics.SELECT_CHANNEL:
        this._selectChannel(payload.channelId);
        break;
      case ChannelEventTopics.MESSAGE_SELECTED: {
        const msg = payload.message as QhorusMessage;
        this._selectedMessageId = msg.id;
        this._replyTo = { messageId: msg.inReplyTo ?? msg.id, senderName: msg.sender };
        break;
      }
      case 'channel:send-message':
        this._sendMessage(payload.content, payload.speechAct, payload.inReplyTo);
        break;
      case 'channel:artefact-selected':
        this._selectedArtefactRef = payload.artefactRef;
        if (!this._dockState['artifacts']) {
          this._dockState = { ...this._dockState, artifacts: true };
        }
        break;
      case 'terminal-resize':
        this._handle?.resize(payload.cols, payload.rows);
        break;
      case 'key-pressed':
        this._handle?.sendInput(payload.code);
        break;
      case 'worker-selected':
        this._handleWorkerSwitch(payload.sessionId, payload.name);
        break;
      case ChannelEventTopics.REACT:
        this._addReaction(payload.messageId, payload.emoji);
        break;
      case ChannelEventTopics.UNREACT:
        this._removeReaction(payload.messageId, payload.emoji);
        break;
      case ChannelEventTopics.SELECT_TOPIC:
        this._viewMode = 'flat';
        break;
      case ChannelEventTopics.VIEW_MODE:
        this._viewMode = payload.mode === 'threaded' ? 'threaded' : 'flat';
        break;
    }
  }

  // ── Channel data flow ────────────────────────────────────────────────────

  private _loadCursors(): void {
    try { const s = sessionStorage.getItem(CURSOR_STORE_KEY); if (s) this._cursors = JSON.parse(s); }
    catch { /* ignore */ }
  }

  private _persistCursors(): void {
    try { sessionStorage.setItem(CURSOR_STORE_KEY, JSON.stringify(this._cursors)); }
    catch { /* ignore */ }
  }

  private _fetchMeshConfig(): void {
    fetch('/api/mesh/config').then(r => r.json())
      .then((cfg: { cursorStalenessMinutes?: number }) => {
        if (cfg?.cursorStalenessMinutes != null) this._stalenessMs = cfg.cursorStalenessMinutes * 60 * 1000;
      }).catch(() => { /* ignore */ });
  }

  private _loadChannels(): void {
    fetch('/api/mesh/channels').then(r => r.json())
      .then((channels: ChannelInfo[]) => {
        channels.sort((a, b) => a.name.localeCompare(b.name));
        this._channelAllowedTypes = {};
        channels.forEach(ch => { this._channelAllowedTypes[ch.name] = ch.allowedTypes || null; });
        this._channels = channels.map(toQhorusChannel);
        const preselect = this._preselect || new URLSearchParams(window.location.search).get('channel');
        if (preselect) { this._selectChannel(preselect); return; }
        if (this._caseId) this._selectCaseChannel(this._caseId);
      }).catch(() => { /* ignore */ });
  }

  private _selectCaseChannel(caseId: string): void {
    const prefix = `case-${caseId}/`;
    const target = this._channels.find(ch => ch.name === `${prefix}work`)
      || this._channels.find(ch => ch.name.startsWith(prefix));
    if (target) this._selectChannel(target.id);
  }

  private _selectChannel(name: string | null): void {
    this._closeEventSource();
    if (this._pollTimer) { clearTimeout(this._pollTimer); this._pollTimer = null; }
    this._showStalePrompt = false;
    this._error = '';
    this._selectedMessageId = undefined;
    this._replyTo = undefined;
    this._selectedArtefactRef = undefined;
    this._loadCursors();
    this._selectedChannelId = name || '';
    if (!name) { this._messages = []; this._commitments = new Map(); return; }

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
    this._fetchCommitments(name);
    this._fetchTopics();
    this._fetchMembers();
  }

  private _openEventSource(name: string): void {
    this._closeEventSource();
    const afterId = this._cursors[name]?.id ?? 0;
    this._eventSource = new EventSource(`/api/mesh/channels/${encodeURIComponent(name)}/events?after=${afterId}`);
    this._eventSource.onmessage = (e: MessageEvent) => {
      try {
        const entries = JSON.parse(e.data) as TimelineEntry[];
        if (Array.isArray(entries) && entries.length) {
          this._appendMessages(name, entries);
          this._fetchCommitments(name);
          this._fetchReactions();
        }
      } catch { /* ignore */ }
    };
    this._eventSource.onerror = () => {
      this._closeEventSource();
      if (this._selectedChannelId) this._pollTimer = setTimeout(() => this._pollChannel(), POLL_MS);
    };
  }

  private _closeEventSource(): void {
    if (this._eventSource) { this._eventSource.close(); this._eventSource = null; }
  }

  private _pollChannel(): void {
    if (!this._selectedChannelId) return;
    const lastId = this._cursors[this._selectedChannelId]?.id ?? 0;
    fetch(`/api/mesh/channels/${encodeURIComponent(this._selectedChannelId)}/timeline?limit=50${lastId ? `&after=${lastId}` : ''}`)
      .then(r => r.ok ? r.json() : undefined)
      .then((e: TimelineEntry[] | undefined) => {
        if (e?.length) {
          this._appendMessages(this._selectedChannelId, e);
          this._fetchCommitments(this._selectedChannelId);
        }
      })
      .catch(() => { /* ignore */ });
    this._pollTimer = setTimeout(() => this._pollChannel(), POLL_MS);
  }

  private _fullLoad(name: string): void {
    fetch(`/api/mesh/channels/${encodeURIComponent(name)}/timeline?limit=100`)
      .then(r => r.ok ? r.json() : undefined)
      .then((e: TimelineEntry[] | undefined) => { if (e?.length) { this._appendMessages(name, e); this._fetchReactions(); } })
      .catch(() => { this._error = 'Failed to load messages.'; });
  }

  private _catchUp(name: string, fromId: number): void {
    fetch(`/api/mesh/channels/${encodeURIComponent(name)}/timeline?limit=50&after=${fromId}`)
      .then(r => r.ok ? r.json() : undefined)
      .then((e: TimelineEntry[] | undefined) => { if (e?.length) this._appendMessages(name, e); })
      .catch(() => { this._error = 'Catch-up failed — some messages may be missing.'; });
  }

  private _appendMessages(channelName: string, entries: TimelineEntry[]): void {
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

  private _fetchCommitments(channelName: string): void {
    fetch(`/api/mesh/channels/${encodeURIComponent(channelName)}/commitments`)
      .then(r => r.ok ? r.json() : [])
      .then((data: Array<{ id: string; correlationId: string; state: string; createdAt?: string; expiresAt?: string | null; acknowledgedAt?: string | null; resolvedAt?: string | null }>) => {
        this._commitments = toCommitmentMap(data);
      })
      .catch(() => { /* ignore */ });
  }

  private _fetchReactions(): void {
    if (!this._selectedChannelId || this._messages.length === 0) { this._reactions = []; return; }
    const messageIds = this._messages.map(m => Number(m.id)).filter(id => !isNaN(id));
    if (messageIds.length === 0) { this._reactions = []; return; }
    fetch(`/api/mesh/channels/${encodeURIComponent(this._selectedChannelId)}/reactions/batch`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ messageIds }),
    })
      .then(r => r.ok ? r.json() : {})
      .then((data: Record<string, Array<{ emoji: string; count: number; actorIds: string[] }>>) => {
        const reactions: Reaction[] = [];
        for (const [msgId, groups] of Object.entries(data)) {
          for (const g of groups) {
            for (const actorId of g.actorIds) {
              reactions.push({ messageId: msgId, emoji: g.emoji, actorId, createdAt: '' });
            }
          }
        }
        this._reactions = reactions;
      })
      .catch(() => { this._reactions = []; });
  }

  private _addReaction(messageId: string, emoji: string): void {
    if (!this._selectedChannelId) return;
    fetch(`/api/mesh/channels/${encodeURIComponent(this._selectedChannelId)}/messages/${messageId}/reactions`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ emoji }),
    }).then(() => this._fetchReactions()).catch(() => { /* ignore */ });
  }

  private _removeReaction(messageId: string, emoji: string): void {
    if (!this._selectedChannelId) return;
    fetch(`/api/mesh/channels/${encodeURIComponent(this._selectedChannelId)}/messages/${messageId}/reactions?emoji=${encodeURIComponent(emoji)}`, {
      method: 'DELETE',
    }).then(() => this._fetchReactions()).catch(() => { /* ignore */ });
  }

  private _fetchTopics(): void {
    if (!this._selectedChannelId) { this._topics = []; return; }
    fetch(`/api/mesh/channels/${encodeURIComponent(this._selectedChannelId)}/topics`)
      .then(r => r.ok ? r.json() : [])
      .then((data: TopicSummaryResponse[]) => {
        this._topics = data.map(s => toQhorusTopic(s, this._selectedChannelId));
      })
      .catch(() => { this._topics = []; });
  }

  private _fetchMembers(): void {
    if (!this._selectedChannelId) { this._members = []; this._memberPresence = []; return; }
    fetch(`/api/mesh/channels/${encodeURIComponent(this._selectedChannelId)}/members`)
      .then(r => r.ok ? r.json() : [])
      .then((data: MembershipResponse[]) => {
        this._members = data.map(m => toChannelMember(m, this._selectedChannelId));
        this._fetchPresence();
      })
      .catch(() => { this._members = []; this._memberPresence = []; });
  }

  private _fetchPresence(): void {
    if (!this._selectedChannelId) { this._memberPresence = []; return; }
    fetch(`/api/mesh/channels/${encodeURIComponent(this._selectedChannelId)}/presence`)
      .then(r => r.ok ? r.json() : { subscribers: 0 })
      .then((data: { subscribers: number }) => {
        this._memberPresence = this._members.map(m => ({
          memberId: m.memberId,
          status: (data.subscribers > 0 ? 'ONLINE' : 'OFFLINE') as 'ONLINE' | 'OFFLINE',
        }));
      })
      .catch(() => { this._memberPresence = []; });
  }

  private _groupThreads(): Array<{ root: QhorusMessage; replies: QhorusMessage[] }> {
    const roots: QhorusMessage[] = [];
    const replyMap = new Map<string, QhorusMessage[]>();
    for (const msg of this._messages) {
      if (msg.inReplyTo) {
        const replies = replyMap.get(msg.inReplyTo) ?? [];
        replies.push(msg);
        replyMap.set(msg.inReplyTo, replies);
      } else {
        roots.push(msg);
      }
    }
    return roots.map(root => ({ root, replies: replyMap.get(root.id) ?? [] }));
  }

  private _sendMessage(content: string, speechAct?: string, inReplyTo?: string): void {
    if (!content?.trim() || !this._selectedChannelId) return;
    this._error = '';
    const body: Record<string, unknown> = { content: content.trim(), type: speechAct || 'command' };
    if (inReplyTo) {
      body.inReplyTo = Number(inReplyTo);
      const replyMsg = this._messages.find(m => m.id === inReplyTo);
      if (replyMsg?.correlationId) body.correlationId = replyMsg.correlationId;
    }
    if (this._replyTo && !inReplyTo) {
      body.inReplyTo = Number(this._replyTo.messageId);
    }
    fetch(`/api/mesh/channels/${encodeURIComponent(this._selectedChannelId)}/messages`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
      .then(r => { if (!r.ok) return r.text().then(t => { throw new Error(t || String(r.status)); }); })
      .then(() => { this._replyTo = undefined; })
      .catch((err: Error) => { this._error = err.message || 'Send failed.'; });
  }

  // ── Stale cursor ─────────────────────────────────────────────────────────

  private _onCatchUp(): void {
    this._showStalePrompt = false;
    if (this._selectedChannelId) {
      this._openEventSource(this._selectedChannelId);
      this._catchUp(this._selectedChannelId, this._staleCursorId);
    }
  }

  private _onReload(): void {
    this._showStalePrompt = false;
    if (this._selectedChannelId) {
      delete this._cursors[this._selectedChannelId];
      this._persistCursors();
      this._openEventSource(this._selectedChannelId);
      this._fullLoad(this._selectedChannelId);
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

    this._selectedChannelId = '';
    this._messages = [];
    this._commitments = new Map();
    this._selectedMessageId = undefined;
    this._replyTo = undefined;
    this._selectedArtefactRef = undefined;
    this._loadChannels();

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

  private _computeAllowedTypes(channelName: string): MessageType[] | undefined {
    const raw = this._channelAllowedTypes[channelName];
    return raw ? raw.split(',').map(t => t.trim().toUpperCase() as MessageType) : undefined;
  }

  // ── Render ───────────────────────────────────────────────────────────────

  override render() {
    const allowedTypes = this._selectedChannelId
      ? this._computeAllowedTypes(this._selectedChannelId) : undefined;

    const activeContextPanel = Object.entries(this._dockState).find(([, v]) => v)?.[0];

    return html`
      ${this._workers.length > 0 ? this._renderWorkerNav() : nothing}

      <div class="main-panel">
        <div class="terminal-area" id="terminal-container"></div>
      </div>

      <div class="conversation-area">
        ${this._caseId ? this._renderCaseHeader() : nothing}
        ${this._lineageExpanded ? this._renderLineage() : nothing}

        ${this._showStalePrompt ? html`
          <div class="stale-prompt">
            <span class="stale-msg">You were away for a while.</span>
            <pages-button variant="ghost" size="sm" label="Catch up from where you left off" @click=${() => this._onCatchUp()}></pages-button>
            <pages-button variant="ghost" size="sm" label="Reload full history" @click=${() => this._onReload()}></pages-button>
          </div>
        ` : nothing}

        <channel-nav .channels=${this._channels} .selectedChannelId=${this._selectedChannelId}></channel-nav>

        ${this._topics.length > 0 ? html`
          <blocks-channel-topic-bar .topics=${this._topics}
            .viewMode=${this._viewMode}></blocks-channel-topic-bar>
        ` : nothing}

        <div class="feed-container">
          ${this._viewMode === 'threaded'
            ? this._groupThreads().map(t => html`
                <blocks-channel-thread .rootMessage=${t.root} .replies=${t.replies}
                  .reactions=${this._reactions.filter(r => r.messageId === t.root.id || t.replies.some(rp => rp.id === r.messageId))}
                  .selectedMessageId=${this._selectedMessageId}></blocks-channel-thread>`)
            : html`<channel-feed .messages=${this._messages} .channelId=${this._selectedChannelId}
                .reactions=${this._reactions} .staleCursorMinutes=${0}></channel-feed>`}
        </div>

        <channel-input .channelId=${this._selectedChannelId} .showTypeSelector=${true}
          .allowedTypes=${allowedTypes} .replyTo=${this._replyTo}></channel-input>

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
          ${this._renderContextPanel(activeContextPanel)}
        </div>
      ` : nothing}
    `;
  }

  private _renderContextPanel(panelId: string) {
    switch (panelId) {
      case 'tasks':
        return html`<channel-task-panel
          .messages=${this._messages}
          .commitments=${this._commitments}
          .selectedMessageId=${this._selectedMessageId}></channel-task-panel>`;
      case 'correlation':
        return html`<channel-correlation-panel
          .messages=${this._messages}
          .commitments=${this._commitments}
          .selectedMessageId=${this._selectedMessageId}></channel-correlation-panel>`;
      case 'artifacts':
        return html`<channel-artifact-panel
          .selectedArtefactRef=${this._selectedArtefactRef}></channel-artifact-panel>`;
      case 'members':
        return html`<blocks-channel-member-panel .members=${this._members} .presence=${this._memberPresence}></blocks-channel-member-panel>`;
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
