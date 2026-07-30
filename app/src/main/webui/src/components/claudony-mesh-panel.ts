import { LitElement, html, css, nothing } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import type { SelectOption } from '@casehubio/pages-ui-components';
import '@casehubio/pages-ui-components';
import { fetchWithAuth } from '../util/auth.js';

interface ChannelData { name: string; messageCount: number; lastActivityAt?: string; allowedTypes?: string }
interface InstanceData { instanceId: string }
interface FeedEntry { id?: number; sender?: string; agent_id?: string; content?: string; channel?: string; created_at?: string }
interface MeshData { channels: ChannelData[]; instances: InstanceData[]; feed: FeedEntry[] }

type MeshView = 'overview' | 'channel' | 'feed';

@customElement('claudony-mesh-panel')
export class ClaudonyMeshPanel extends LitElement {
  @state() private _data: MeshData = { channels: [], instances: [], feed: [] };
  @state() private _activeView: MeshView = 'overview';
  @state() private _collapsed = false;
  @state() private _selectedChannel = '';

  private _strategy: 'sse' | 'poll' = 'poll';
  private _pollInterval = 3000;
  private _pollTimer: ReturnType<typeof setInterval> | null = null;
  private _eventSource: EventSource | null = null;
  private _lastEventId = -1;

  private _dockChannel = '';
  private _dockType = 'status';

  private _typeOptions: SelectOption[] = [
    { value: 'status', label: 'status' }, { value: 'query', label: 'query' },
    { value: 'command', label: 'command' }, { value: 'response', label: 'response' },
    { value: 'decline', label: 'decline' }, { value: 'handoff', label: 'handoff' },
    { value: 'done', label: 'done' },
  ];

  static override styles = css`
    :host {
      width: 300px; min-width: 300px; background: var(--pages-neutral-2);
      border-left: 1px solid var(--pages-neutral-4);
      display: flex; flex-direction: column; overflow: hidden;
      transition: width 0.2s ease, min-width 0.2s ease; flex-shrink: 0;
    }
    :host(.collapsed) { width: 0; min-width: 0; }
    .header {
      display: flex; align-items: center; gap: 6px;
      padding: 10px 12px; border-bottom: 1px solid var(--pages-neutral-4); flex-shrink: 0;
    }
    .title {
      font-size: 11px; font-weight: 600; color: var(--pages-success-9);
      flex: 1; letter-spacing: 0.05em;
    }
    .view-switcher { display: flex; gap: 2px; }
    .body { flex: 1; overflow-y: auto; padding: 12px; }
    .empty { color: var(--pages-neutral-8); font-size: 13px; text-align: center; padding: 24px 0; }
    .section { margin-bottom: 12px; }
    .label { font-size: 10px; font-weight: 600; color: var(--pages-neutral-8); letter-spacing: 0.05em; margin-bottom: 4px; }
    .presence { display: flex; flex-wrap: wrap; gap: 4px; margin-bottom: 4px; }
    .channel-item {
      display: flex; justify-content: space-between; align-items: center;
      padding: 5px 6px; border-radius: var(--pages-radius-sm, 4px); cursor: pointer; margin-bottom: 2px;
    }
    .channel-item:hover { background: var(--pages-neutral-1); }
    .channel-name { font-size: 12px; color: var(--pages-success-9); }
    .channel-count {
      font-size: 10px; background: var(--pages-neutral-1); border-radius: 8px;
      padding: 0 5px; color: var(--pages-neutral-8);
    }
    .msg { font-size: 11px; margin-bottom: 3px; line-height: 1.4; }
    .msg-sender { color: var(--pages-success-9); font-weight: 600; margin-right: 4px; }
    .msg-content { color: var(--pages-neutral-8); }
    .dim { color: var(--pages-neutral-8); font-size: 12px; }
    .feed-item {
      font-size: 11px; margin-bottom: 4px; display: flex; gap: 5px; align-items: baseline; line-height: 1.4;
    }
    .feed-tag { color: var(--pages-success-9); font-size: 10px; flex-shrink: 0; cursor: pointer; }
    .presence-footer {
      margin-top: 8px; padding-top: 8px; border-top: 1px solid var(--pages-neutral-4);
      display: flex; flex-wrap: wrap; gap: 6px;
    }
    .dock {
      border-top: 1px solid var(--pages-neutral-4); padding: 8px;
      display: flex; flex-direction: column; gap: 4px; flex-shrink: 0;
    }
    .dock-controls { display: flex; gap: 4px; }
    .dock-footer { display: flex; align-items: center; gap: 6px; }
    .dock-error { font-size: 0.7rem; color: var(--pages-danger-9); flex: 1; }
    .expand-btn {
      position: fixed; right: 0; top: 50%; transform: translateY(-50%);
      background: var(--pages-neutral-2); border: 1px solid var(--pages-neutral-4); border-right: none;
      color: var(--pages-success-9); padding: 10px 5px; writing-mode: vertical-rl;
      font-size: 10px; font-weight: 600; letter-spacing: 0.05em;
      border-radius: var(--pages-radius-sm, 4px) 0 0 var(--pages-radius-sm, 4px);
      cursor: pointer; z-index: 5;
    }
    .ch-select {
      flex: 1; background: var(--pages-neutral-2); color: var(--pages-neutral-11);
      border: 1px solid var(--pages-neutral-4); border-radius: 3px; font-size: 12px; padding: 4px 6px;
    }
  `;

  connectedCallback(): void {
    super.connectedCallback();
    const saved = localStorage.getItem('mesh-view');
    if (saved === 'overview' || saved === 'channel' || saved === 'feed') this._activeView = saved;
    this._collapsed = localStorage.getItem('mesh-collapsed') === 'true';
    if (this._collapsed) this.classList.add('collapsed');
    this._initStrategy();
  }

  disconnectedCallback(): void {
    super.disconnectedCallback();
    this._stopStrategy();
  }

  private async _initStrategy(): Promise<void> {
    try {
      const cfg = await fetch('/api/mesh/config').then(r => r.json());
      this._strategy = cfg.strategy === 'sse' ? 'sse' : 'poll';
      this._pollInterval = cfg.interval || 3000;
      this._startStrategy();
    } catch { /* ignore */ }
  }

  private _startStrategy(): void {
    if (this._strategy === 'sse') {
      this._connectSSE();
    } else {
      this._poll();
      this._pollTimer = setInterval(() => this._poll(), this._pollInterval);
    }
  }

  private _stopStrategy(): void {
    if (this._pollTimer) { clearInterval(this._pollTimer); this._pollTimer = null; }
    if (this._eventSource) { this._eventSource.close(); this._eventSource = null; }
  }

  private _connectSSE(): void {
    const url = this._lastEventId >= 0 ? '/api/mesh/events?after=' + this._lastEventId : '/api/mesh/events';
    this._eventSource = new EventSource(url);
    this._eventSource.onmessage = (e) => {
      try {
        const data = JSON.parse(e.data);
        if (typeof data._eventId === 'number') this._lastEventId = data._eventId;
        this._update(data);
      } catch { /* ignore */ }
    };
    this._eventSource.onerror = () => {
      if (this._eventSource) { this._eventSource.close(); this._eventSource = null; }
      setTimeout(() => { if (!this._eventSource) this._connectSSE(); }, 2000);
    };
  }

  private async _poll(): Promise<void> {
    try {
      const [channels, instances, feed] = await Promise.all([
        fetch('/api/mesh/channels').then(r => r.json()),
        fetch('/api/mesh/instances').then(r => r.json()),
        fetch('/api/mesh/feed?limit=100').then(r => r.json()),
      ]);
      this._update({ channels, instances, feed });
    } catch { /* ignore */ }
  }

  private _update(data: MeshData): void {
    this._data = data;
    if (data.channels?.length && !this._dockChannel) {
      const sorted = [...data.channels].sort((a, b) => (b.lastActivityAt || '').localeCompare(a.lastActivityAt || ''));
      this._dockChannel = sorted[0]?.name || '';
    }
  }

  private _switchView(view: MeshView): void {
    this._activeView = view;
    localStorage.setItem('mesh-view', view);
  }

  private _collapse(): void {
    this._collapsed = true;
    this.classList.add('collapsed');
    localStorage.setItem('mesh-collapsed', 'true');
  }

  private _expand(): void {
    this._collapsed = false;
    this.classList.remove('collapsed');
    localStorage.setItem('mesh-collapsed', 'false');
  }

  private _selectChannel(name: string): void {
    this._selectedChannel = name;
    this._dockChannel = name;
  }

  private async _sendMessage(): Promise<void> {
    const textarea = this.renderRoot.querySelector('#dock-textarea') as HTMLTextAreaElement | null;
    const content = textarea?.value?.trim();
    if (!content || !this._dockChannel) return;
    try {
      const resp = await fetch(
        '/api/mesh/channels/' + encodeURIComponent(this._dockChannel) + '/messages',
        { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ content, type: this._dockType }) }
      );
      if (!resp.ok) { const text = await resp.text(); this._showDockError(text || 'Send failed (' + resp.status + ')'); return; }
      if (textarea) textarea.value = '';
      if (this._strategy === 'poll') { this._stopStrategy(); this._poll(); this._pollTimer = setInterval(() => this._poll(), this._pollInterval); }
    } catch (e: any) { this._showDockError(e.message || 'Send failed'); }
  }

  private _showDockError(msg: string): void {
    const el = this.renderRoot.querySelector('#dock-error');
    if (el) { el.textContent = msg; setTimeout(() => { el.textContent = ''; }, 4000); }
  }

  override render() {
    const hasChannels = this._data.channels.length > 0;
    const channelOptions: SelectOption[] = hasChannels
      ? [...this._data.channels].sort((a, b) => (b.lastActivityAt || '').localeCompare(a.lastActivityAt || ''))
          .map(c => ({ value: c.name, label: '#' + c.name }))
      : [{ value: '', label: '— no channels —' }];

    return html`
      <div class="header">
        <span class="title">MESH</span>
        <div class="view-switcher">
          ${(['overview', 'channel', 'feed'] as const).map(v => html`
            <pages-button size="xs" variant=${this._activeView === v ? 'primary' : 'ghost'}
              label=${v === 'overview' ? '◎' : v === 'channel' ? '#' : '≡'}
              title=${v.charAt(0).toUpperCase() + v.slice(1)}
              @click=${() => this._switchView(v)}></pages-button>
          `)}
        </div>
        <pages-button size="xs" variant="ghost" label="←" title="Collapse"
          @click=${() => this._collapse()}></pages-button>
      </div>

      <div class="body">
        ${this._activeView === 'overview' ? this._renderOverview()
          : this._activeView === 'channel' ? this._renderChannel()
          : this._renderFeed()}
      </div>

      <div class="dock">
        <div class="dock-controls">
          <select class="ch-select" ?disabled=${!hasChannels}
            @change=${(e: Event) => { this._dockChannel = (e.target as HTMLSelectElement).value; }}>
            ${channelOptions.map(o => html`<option value=${o.value} ?selected=${o.value === this._dockChannel}>${o.label}</option>`)}
          </select>
          <pages-select .options=${this._typeOptions} value="status" style="flex:1"
            @change=${(e: Event) => { this._dockType = (e.target as any).value; }}></pages-select>
        </div>
        <textarea id="dock-textarea" rows="2" placeholder="Type a message… (Enter to send)"
          style="width:100%;resize:none;background:var(--pages-neutral-1);color:var(--pages-neutral-11);border:1px solid var(--pages-neutral-4);border-radius:3px;padding:4px 6px;font-size:0.8rem;font-family:inherit;box-sizing:border-box"
          @keydown=${(e: KeyboardEvent) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); this._sendMessage(); } }}></textarea>
        <div class="dock-footer">
          <pages-button size="xs" variant="primary" label="Send" ?disabled=${!hasChannels}
            @click=${() => this._sendMessage()}></pages-button>
          <span id="dock-error" class="dock-error"></span>
        </div>
      </div>

      ${this._collapsed ? html`
        <pages-button class="expand-btn" variant="ghost" label="MESH" title="Open Mesh panel"
          @click=${() => this._expand()}></pages-button>
      ` : nothing}
    `;
  }

  private _renderOverview() {
    const { channels, instances, feed } = this._data;
    if (!channels.length && !instances.length) return html`<div class="empty">No active channels</div>`;

    return html`
      <div class="section">
        <div class="label">ONLINE</div>
        <div class="presence">
          ${instances.length
            ? instances.map(i => html`<pages-badge label=${i.instanceId} variant="success" size="sm"></pages-badge>`)
            : html`<span class="dim">No agents online</span>`}
        </div>
      </div>
      <div class="section">
        <div class="label">CHANNELS</div>
        ${channels.length
          ? channels.map(ch => html`
              <div class="channel-item" @click=${() => { this._selectChannel(ch.name); this._switchView('channel'); }}>
                <span class="channel-name">#${ch.name}</span>
                <span class="channel-count">${ch.messageCount}</span>
              </div>`)
          : html`<span class="dim">No channels</span>`}
      </div>
      ${feed?.length ? html`
        <div class="section">
          <div class="label">RECENT</div>
          ${feed.slice(0, 5).map(m => html`
            <div class="msg">
              <span class="msg-sender">${m.sender || m.agent_id || '?'}</span>
              <span class="msg-content">${(m.content || '').substring(0, 60)}</span>
            </div>`)}
        </div>
      ` : nothing}
    `;
  }

  private _renderChannel() {
    const { channels, feed } = this._data;
    if (!channels.length) return html`<div class="empty">No active channels</div>`;

    if (!this._selectedChannel || !channels.find(c => c.name === this._selectedChannel)) {
      this._selectedChannel = channels[0]!.name;
    }

    const filtered = (feed || []).filter(m => m.channel === this._selectedChannel);
    return html`
      <select class="ch-select" style="margin-bottom:8px"
        @change=${(e: Event) => { this._selectedChannel = (e.target as HTMLSelectElement).value; this._dockChannel = this._selectedChannel; }}>
        ${channels.map(ch => html`<option value=${ch.name} ?selected=${ch.name === this._selectedChannel}>#${ch.name}</option>`)}
      </select>
      <div>
        ${filtered.length
          ? filtered.map(m => html`<div class="msg"><span class="msg-sender">${m.sender || m.agent_id || '?'}</span><span class="msg-content">${(m.content || '').substring(0, 80)}</span></div>`)
          : html`<span class="dim">No messages</span>`}
      </div>
    `;
  }

  private _renderFeed() {
    const { feed, instances } = this._data;
    if (!feed?.length) return html`<div class="empty">No recent activity</div>`;

    return html`
      <div>
        ${feed.slice(0, 50).map(m => html`
          <div class="feed-item">
            <span class="dim">${(m.created_at || '').substring(11, 19)}</span>
            <span class="feed-tag" @click=${() => this._selectChannel(m.channel || '')}>#${m.channel || '?'}</span>
            <span class="msg-sender">${m.sender || m.agent_id || '?'}</span>
            <span class="msg-content">${(m.content || '').substring(0, 55)}</span>
          </div>`)}
      </div>
      ${instances?.length ? html`
        <div class="presence-footer">
          ${instances.map(i => html`<pages-badge label=${'● ' + i.instanceId} variant="success" size="sm"></pages-badge>`)}
        </div>
      ` : nothing}
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'claudony-mesh-panel': ClaudonyMeshPanel;
  }
}
