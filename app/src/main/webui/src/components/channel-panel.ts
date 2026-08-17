import { LitElement, html, css, nothing, type PropertyValues } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import {
  ChannelEventTopics,
  PushController, ALL_TOPICS,
  ChannelStateController,
  MessagingController,
} from '@casehubio/blocks-ui-channel-activity';
import type { SendMessagePayload, MessageType } from '@casehubio/blocks-ui-channel-activity';
import '@casehubio/blocks-ui-channel-activity';
import '@casehubio/pages-ui-components';
import { createEventConnection } from '@casehubio/pages-data/dataset/external/sources/event-connection.js';
import type { EventConnection } from '@casehubio/pages-data/dataset/external/sources/event-connection.js';
import { fetchWithAuth } from '../util/auth.js';

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

@customElement('claudony-channel-panel')
export class ClaudonyChannelPanel extends LitElement {
  // ── Controllers ─────────────────────────────────────────────────────────
  private _push = new PushController(this);
  private _channels = new ChannelStateController(this, this._push);
  private _messaging = new MessagingController(this, this._channels, {
    restBase: '/api',
    fetch: fetchWithAuth,
  });

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

  private _sessionId = '';
  private _preselect: string | null = null;
  private _eventConn?: EventConnection;
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

    .feed-container { flex: 1; overflow: hidden; display: flex; flex-direction: column; }

    .case-header {
      border-bottom: 1px solid var(--pages-neutral-4, #3e3e42);
      padding: 6px 10px 4px; flex-shrink: 0;
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
    @media (max-width: 1024px) {
      :host { display: none; width: 0; min-width: 0; }
      :host(:not(.collapsed)) {
        display: flex; position: fixed; z-index: 100;
        right: 0; top: 0; bottom: 0; width: 300px; min-width: 300px;
        box-shadow: -4px 0 8px rgba(0,0,0,0.3);
      }
    }
    @media (max-width: 767px) {
      :host { display: none; width: 0; min-width: 0; }
    }
  `;

  override updated(changed: PropertyValues): void {
    if (changed.has('_collapsed')) {
      this.classList.toggle('collapsed', this._collapsed);
    }
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
    this._eventConn?.close();
    if (this._lineagePollTimer) { clearTimeout(this._lineagePollTimer); this._lineagePollTimer = null; }
    if (this._elapsedTicker) { clearInterval(this._elapsedTicker); this._elapsedTicker = null; }
  }

  open(): void {
    this._collapsed = false;
    if (this._caseId && !this._lineageLoaded) {
      this._loadLineage();
      this._startElapsedTicker();
    }
    if (!this._eventConn) this._connectPush();
  }

  close(): void {
    this._collapsed = true;
  }

  toggle(): void {
    if (this._collapsed) this.open(); else this.close();
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
      if (detail?.payload) this._push.applyOp(detail.payload as any);
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

  override render() {
    const messages = this._channels.filteredMessages();

    return html`
      <div class="header">
        <channel-nav style="flex:1" .channels=${this._channels.channels}
          .selectedChannelId=${this._channels.selectedChannelId}></channel-nav>
        <pages-button variant="ghost" size="xs" label="\u{00D7}" title="Close" @click=${() => this.close()}></pages-button>
      </div>

      ${this._caseId ? this._renderCaseHeader() : nothing}
      ${this._lineageExpanded ? this._renderLineage() : nothing}

      <div class="feed-container">
        <channel-feed .messages=${messages}
          .channelId=${this._channels.selectedChannelId}
          .staleCursorMinutes=${0}></channel-feed>
      </div>

      <channel-input .channelId=${this._channels.selectedChannelId}
        .showTypeSelector=${true}
        @pages-event=${(e: CustomEvent) => this._onPagesEvent(e)}></channel-input>

      ${this._error ? html`<div class="error">${this._error}</div>` : nothing}
    `;
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

  private _onPagesEvent(e: CustomEvent) {
    const { topic, payload } = e.detail ?? {};
    if (topic === ChannelEventTopics.SEND_MESSAGE || topic === 'channel:send-message') {
      this._sendMessage(payload as SendMessagePayload);
    } else {
      this._channels.handleEvent(topic, payload);
      this._messaging.handleEvent(topic, payload);
    }
  }

  private async _sendMessage(payload: SendMessagePayload): Promise<void> {
    const channelName = this._channels.selectedChannelId;
    if (!payload.content?.trim() || !channelName) return;
    this._error = '';

    try {
      const res = await fetchWithAuth(`/api/mesh/channels/${encodeURIComponent(channelName)}/messages`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ content: payload.content.trim(), type: payload.speechAct || 'command' }),
      });
      if (!res.ok) {
        const text = await res.text();
        throw new Error(text || String(res.status));
      }
    } catch (err) {
      this._error = (err as Error).message || 'Send failed.';
    }
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
    if (!this._sessionCreatedAt) { this._elapsed = '\u{2014}'; return; }
    const diffM = Math.floor((Date.now() - this._sessionCreatedAt.getTime()) / 60000);
    if (diffM < 1) this._elapsed = '<1m';
    else if (diffM < 60) this._elapsed = `${diffM}m`;
    else this._elapsed = `${Math.floor(diffM / 60)}h ${diffM % 60}m`;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'claudony-channel-panel': ClaudonyChannelPanel;
  }
}
