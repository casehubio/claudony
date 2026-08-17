import { LitElement, html, css, nothing } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import type { SelectOption } from '@casehubio/pages-ui-components';
import '@casehubio/pages-ui-components';
import type { QhorusMessage, Reaction, ChannelMember, PresenceState } from '@casehubio/blocks-ui-channel-activity';
import '@casehubio/blocks-ui-channel-activity';
import { fetchWithAuth } from '../util/auth.js';
import type { MessageType, ArtefactRef } from '@casehubio/blocks-ui-channel-activity';

interface TimelineEntry {
  id?: number;
  type?: string;
  message_type?: string;
  sender?: string;
  content?: string | null;
  created_at?: string;
  agent_id?: string;
  in_reply_to?: number | null;
  correlation_id?: string;
  artefact_refs?: ArtefactRef[] | null;
  target?: string | null;
  reply_count?: number;
  deadline?: string | null;
  topic?: string | null;
}

interface MembershipResponse {
  id: number;
  channelId: string;
  memberId: string;
  role: string;
  tenancyId: string;
  joinedAt: string;
  lastReadMessageId: number | null;
  lastDeliveredMessageId: number | null;
}

function resolveActorType(sender: string): 'HUMAN' | 'AGENT' | 'SYSTEM' {
  if (sender === 'human' || sender.startsWith('human:')) return 'HUMAN';
  if (sender === 'system') return 'SYSTEM';
  return 'AGENT';
}

function toQhorusMessage(entry: Partial<TimelineEntry>): QhorusMessage {
  const isEvent = entry.type === 'EVENT';
  const sender = isEvent ? (entry.agent_id || 'system') : (entry.sender || 'unknown');
  const rawType = isEvent ? 'EVENT' : (entry.message_type || 'status');
  return {
    id: String(entry.id ?? 0),
    channelId: '',
    sender,
    messageType: rawType.toUpperCase() as MessageType,
    actorType: resolveActorType(sender),
    content: entry.content ?? '',
    topic: entry.topic ?? '',
    correlationId: entry.correlation_id,
    inReplyTo: entry.in_reply_to ? String(entry.in_reply_to) : undefined,
    artefactRefs: entry.artefact_refs ?? [],
    target: entry.target ?? undefined,
    replyCount: entry.reply_count ?? 0,
    deadline: entry.deadline ?? undefined,
    createdAt: entry.created_at || new Date().toISOString(),
  };
}

function toChannelMember(m: MembershipResponse, channelName: string): ChannelMember {
  return {
    channelId: channelName,
    memberId: m.memberId,
    displayName: m.memberId,
    role: m.role as 'PARTICIPANT' | 'OBSERVER' | 'MODERATOR',
    actorType: resolveActorType(m.memberId),
  };
}

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
  @state() private _channelMessages: QhorusMessage[] = [];
  @state() private _channelReactions: Reaction[] = [];
  @state() private _channelPresence = 0;
  @state() private _channelMembers: ChannelMember[] = [];
  @state() private _channelMemberPresence: PresenceState[] = [];
  @state() private _showCreateForm = false;
  @state() private _createError = '';

  private _pollInterval = 3000;
  private _pollTimer: ReturnType<typeof setInterval> | null = null;

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
      font-size: var(--pages-font-size-sm); font-weight: 600; color: var(--pages-success-9);
      flex: 1; letter-spacing: 0.05em;
    }
    .view-switcher { display: flex; gap: 2px; }
    .body { flex: 1; overflow-y: auto; padding: 12px; }
    .empty { color: var(--pages-neutral-8); font-size: var(--pages-font-size-base); text-align: center; padding: 24px 0; }
    .section { margin-bottom: 12px; }
    .label { font-size: var(--pages-font-size-xs); font-weight: 600; color: var(--pages-neutral-8); letter-spacing: 0.05em; margin-bottom: 4px; }
    .presence { display: flex; flex-wrap: wrap; gap: 4px; margin-bottom: 4px; }
    .channel-item {
      display: flex; justify-content: space-between; align-items: center;
      padding: 5px 6px; border-radius: var(--pages-radius-sm, 4px); cursor: pointer; margin-bottom: 2px;
    }
    .channel-item:hover { background: var(--pages-neutral-1); }
    .channel-name { font-size: var(--pages-font-size-base); color: var(--pages-success-9); }
    .channel-count {
      font-size: var(--pages-font-size-xs); background: var(--pages-neutral-1); border-radius: 8px;
      padding: 0 5px; color: var(--pages-neutral-8);
    }
    .msg { font-size: var(--pages-font-size-sm); margin-bottom: 3px; line-height: 1.4; }
    .msg-sender { color: var(--pages-success-9); font-weight: 600; margin-right: 4px; }
    .msg-content { color: var(--pages-neutral-8); }
    .dim { color: var(--pages-neutral-8); font-size: var(--pages-font-size-base); }
    .feed-item {
      font-size: var(--pages-font-size-sm); margin-bottom: 4px; display: flex; gap: 5px; align-items: baseline; line-height: 1.4;
    }
    .feed-tag { color: var(--pages-success-9); font-size: var(--pages-font-size-xs); flex-shrink: 0; cursor: pointer; }
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
    .dock-error { font-size: var(--pages-font-size-sm); color: var(--pages-danger-9); flex: 1; }
    .expand-btn {
      position: fixed; right: 0; top: 50%; transform: translateY(-50%);
      background: var(--pages-neutral-2); border: 1px solid var(--pages-neutral-4); border-right: none;
      color: var(--pages-success-9); padding: 10px 5px; writing-mode: vertical-rl;
      font-size: var(--pages-font-size-xs); font-weight: 600; letter-spacing: 0.05em;
      border-radius: var(--pages-radius-sm, 4px) 0 0 var(--pages-radius-sm, 4px);
      cursor: pointer; z-index: 5;
    }
    .create-form {
      padding: 6px; margin-bottom: 8px; background: var(--pages-neutral-1);
      border-radius: var(--pages-radius-sm, 4px); border: 1px solid var(--pages-neutral-4);
    }
    .create-form input {
      width: 100%; padding: 4px 6px; font-size: var(--pages-font-size-base); margin-bottom: 4px;
      background: var(--pages-neutral-2); color: var(--pages-neutral-11);
      border: 1px solid var(--pages-neutral-4); border-radius: 3px;
      font-family: inherit; box-sizing: border-box;
    }
    .create-form .create-actions { display: flex; gap: 4px; align-items: center; }
    .create-form .create-error { font-size: var(--pages-font-size-xs); color: var(--pages-danger-9); flex: 1; }
    .section-header { display: flex; align-items: center; gap: 4px; }
    .section-header .label { flex: 1; margin-bottom: 0; }
    .ch-select {
      flex: 1; background: var(--pages-neutral-2); color: var(--pages-neutral-11);
      border: 1px solid var(--pages-neutral-4); border-radius: 3px; font-size: var(--pages-font-size-base); padding: 4px 6px;
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
      const cfg = await fetchWithAuth('/api/mesh/config').then(r => r.json());
      this._pollInterval = cfg.interval || 3000;
    } catch { /* ignore */ }
    this._poll();
    this._pollTimer = setInterval(() => this._poll(), this._pollInterval);
  }

  private _stopStrategy(): void {
    if (this._pollTimer) { clearInterval(this._pollTimer); this._pollTimer = null; }
  }

  private async _poll(): Promise<void> {
    try {
      const [channels, instances, feed] = await Promise.all([
        fetchWithAuth('/api/channels').then(r => r.json()),
        fetchWithAuth('/api/mesh/instances').then(r => r.json()),
        fetchWithAuth('/api/channels/feed?limit=100').then(r => r.json()),
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
    this._fetchChannelTimeline(name).then(() => this._fetchChannelReactions(name));
    this._autoJoinChannel(name);
    this._fetchChannelMembers(name);
    this._fetchChannelPresence(name);
  }

  private async _autoJoinChannel(name: string): Promise<void> {
    try {
      await fetchWithAuth(`/api/channels/${encodeURIComponent(name)}/members`, { method: 'POST' });
    } catch { /* best-effort */ }
  }

  private async _fetchChannelMembers(name: string): Promise<void> {
    try {
      const resp = await fetchWithAuth(`/api/channels/${encodeURIComponent(name)}/members`);
      if (!resp.ok) { this._channelMembers = []; return; }
      const data: MembershipResponse[] = await resp.json();
      this._channelMembers = data.map(m => toChannelMember(m, name));
    } catch { this._channelMembers = []; }
  }

  private async _fetchChannelTimeline(name: string): Promise<void> {
    try {
      const resp = await fetchWithAuth(`/api/channels/${encodeURIComponent(name)}/timeline?limit=50`);
      if (!resp.ok) { this._channelMessages = []; return; }
      const data: TimelineEntry[] = await resp.json();
      this._channelMessages = data.map(e => toQhorusMessage(e));
    } catch { this._channelMessages = []; }
  }

  private async _fetchChannelReactions(name: string): Promise<void> {
    if (this._channelMessages.length === 0) { this._channelReactions = []; return; }
    const messageIds = this._channelMessages.map(m => Number(m.id)).filter(id => !isNaN(id));
    if (messageIds.length === 0) { this._channelReactions = []; return; }
    try {
      const resp = await fetchWithAuth(`/api/channels/${encodeURIComponent(name)}/reactions/batch`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ messageIds }),
      });
      if (!resp.ok) { this._channelReactions = []; return; }
      const data: Record<string, Array<{ emoji: string; count: number; actorIds: string[] }>> = await resp.json();
      const reactions: Reaction[] = [];
      for (const [msgId, groups] of Object.entries(data)) {
        for (const g of groups) {
          for (const actorId of g.actorIds) {
            reactions.push({ messageId: msgId, emoji: g.emoji, actorId, createdAt: '' });
          }
        }
      }
      this._channelReactions = reactions;
    } catch { this._channelReactions = []; }
  }

  private async _fetchChannelPresence(name: string): Promise<void> {
    try {
      const resp = await fetchWithAuth(`/api/channels/${encodeURIComponent(name)}/presence`);
      if (!resp.ok) { this._channelPresence = 0; this._channelMemberPresence = []; return; }
      const data = await resp.json();
      this._channelPresence = data.subscribers ?? 0;
      this._channelMemberPresence = this._channelMembers.map(m => ({
        memberId: m.memberId,
        status: (this._channelPresence > 0 ? 'ONLINE' : 'OFFLINE') as 'ONLINE' | 'OFFLINE',
      }));
    } catch { this._channelPresence = 0; this._channelMemberPresence = []; }
  }

  private async _createChannel(): Promise<void> {
    const nameInput = this.renderRoot.querySelector('#create-name') as HTMLInputElement | null;
    const descInput = this.renderRoot.querySelector('#create-desc') as HTMLInputElement | null;
    const name = nameInput?.value?.trim();
    if (!name) { this._createError = 'Name is required'; return; }
    try {
      const resp = await fetchWithAuth('/api/channels', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name, description: descInput?.value?.trim() || null }),
      });
      if (!resp.ok) {
        const text = await resp.text();
        this._createError = text || `Failed (${resp.status})`;
        return;
      }
      this._showCreateForm = false;
      this._createError = '';
      if (nameInput) nameInput.value = '';
      if (descInput) descInput.value = '';
      this._poll();
    } catch (e: any) {
      this._createError = e.message || 'Create failed';
    }
  }

  private async _sendMessage(): Promise<void> {
    const textarea = this.renderRoot.querySelector('#dock-textarea') as HTMLTextAreaElement | null;
    const content = textarea?.value?.trim();
    if (!content || !this._dockChannel) return;
    try {
      const resp = await fetchWithAuth(
        '/api/mesh/channels/' + encodeURIComponent(this._dockChannel) + '/messages',
        { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ content, type: this._dockType }) }
      );
      if (!resp.ok) { const text = await resp.text(); this._showDockError(text || 'Send failed (' + resp.status + ')'); return; }
      if (textarea) textarea.value = '';
      this._stopStrategy(); this._poll(); this._pollTimer = setInterval(() => this._poll(), this._pollInterval);
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
          style="width:100%;resize:none;background:var(--pages-neutral-1);color:var(--pages-neutral-11);border:1px solid var(--pages-neutral-4);border-radius:3px;padding:4px 6px;font-size:var(--pages-font-size-base);font-family:inherit;box-sizing:border-box"
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
        <div class="section-header">
          <div class="label">CHANNELS</div>
          <pages-button size="xs" variant="ghost" label="+" title="Create channel"
            @click=${() => { this._showCreateForm = !this._showCreateForm; this._createError = ''; }}></pages-button>
        </div>
        ${this._showCreateForm ? html`
          <div class="create-form">
            <input id="create-name" type="text" placeholder="Channel name (e.g. team-engineering)" />
            <input id="create-desc" type="text" placeholder="Description (optional)" />
            <div class="create-actions">
              <pages-button size="xs" variant="primary" label="Create"
                @click=${() => this._createChannel()}></pages-button>
              <pages-button size="xs" variant="ghost" label="Cancel"
                @click=${() => { this._showCreateForm = false; this._createError = ''; }}></pages-button>
              <span class="create-error">${this._createError}</span>
            </div>
          </div>
        ` : nothing}
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
    const { channels } = this._data;
    if (!channels.length) return html`<div class="empty">No active channels</div>`;

    const selected = (!this._selectedChannel || !channels.find(c => c.name === this._selectedChannel))
      ? channels[0]!.name : this._selectedChannel;

    if (selected !== this._selectedChannel) {
      this._selectChannel(selected);
    }

    return html`
      <div style="display:flex; align-items:center; gap:8px; margin-bottom:8px">
        <select class="ch-select" style="flex:1"
          @change=${(e: Event) => this._selectChannel((e.target as HTMLSelectElement).value)}>
          ${channels.map(ch => html`<option value=${ch.name} ?selected=${ch.name === selected}>#${ch.name}</option>`)}
        </select>
        ${this._channelPresence > 0 ? html`
          <pages-badge label="${this._channelPresence} watching" variant="info" size="sm"></pages-badge>
        ` : nothing}
      </div>
      <channel-feed .messages=${this._channelMessages}
        .channelId=${selected}
        .reactions=${this._channelReactions}
        .staleCursorMinutes=${0}></channel-feed>
      ${this._channelMembers.length > 0 ? html`
        <div style="border-top:1px solid var(--pages-neutral-4); margin-top:8px; padding-top:4px">
          <blocks-channel-member-panel .members=${this._channelMembers}
            .presence=${this._channelMemberPresence}></blocks-channel-member-panel>
        </div>
      ` : nothing}
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
