import { LitElement, html, css, nothing } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import type { SelectOption } from '@casehubio/pages-ui-components';
import '@casehubio/pages-ui-components';
import '@casehubio/pages-primitives/modal';
import { fetchWithAuth } from '../util/auth.js';

interface Peer {
  id: string;
  url: string;
  name?: string;
  health: string;
  circuitState: string;
  source: string;
  terminalMode: string;
  lastSeen?: string;
  sessionCount: number;
}

@customElement('claudony-fleet-panel')
export class ClaudonyFleetPanel extends LitElement {
  @state() private _peers: Peer[] = [];
  @state() private _showAddDialog = false;

  private _pollTimer: ReturnType<typeof setInterval> | null = null;
  private _addUrl = '';
  private _addName = '';
  private _addMode = 'DIRECT';

  peerTerminalModes: Record<string, { id: string; terminalMode: string }> = {};

  private _modeOptions: SelectOption[] = [
    { value: 'DIRECT', label: 'Direct — browser connects to peer directly' },
    { value: 'PROXY', label: 'Proxy — traffic routed through this server' },
  ];

  static override styles = css`
    :host {
      width: 240px; min-width: 200px; background: var(--pages-neutral-2);
      border-right: 1px solid var(--pages-neutral-4);
      display: flex; flex-direction: column; overflow-y: auto; flex-shrink: 0;
    }
    .header {
      display: flex; align-items: center; justify-content: space-between;
      padding: 12px 14px; border-bottom: 1px solid var(--pages-neutral-4);
      position: sticky; top: 0; background: var(--pages-neutral-2); z-index: 1;
    }
    .title {
      font-size: var(--pages-font-size-base); font-weight: 600; text-transform: uppercase;
      letter-spacing: .5px; color: var(--pages-neutral-8);
    }
    .peer-empty {
      padding: 16px 14px; font-size: var(--pages-font-size-base); color: var(--pages-neutral-8); font-style: italic;
    }
    .peer-card {
      padding: 10px 14px; border-bottom: 1px solid var(--pages-neutral-4); font-size: var(--pages-font-size-base);
    }
    .peer-header { display: flex; align-items: center; gap: 6px; margin-bottom: 4px; }
    .peer-name {
      font-weight: 600; font-size: var(--pages-font-size-base); flex: 1;
      overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    }
    .peer-source {
      font-size: var(--pages-font-size-xs); color: var(--pages-neutral-8); background: rgba(255,255,255,.05);
      border-radius: 4px; padding: 1px 5px; flex-shrink: 0;
    }
    .peer-url {
      color: var(--pages-neutral-8); font-family: Menlo, Monaco, monospace;
      font-size: var(--pages-font-size-xs); overflow: hidden; text-overflow: ellipsis;
      white-space: nowrap; margin-bottom: 4px;
    }
    .peer-meta {
      display: flex; align-items: center; gap: 6px; margin-bottom: 6px;
      color: var(--pages-neutral-8); font-size: var(--pages-font-size-sm);
    }
    .peer-actions { display: flex; gap: 4px; flex-wrap: wrap; }
    .form-field { margin-bottom: 12px; }

    @media (max-width: 767px) { :host { display: none; } }
  `;

  connectedCallback(): void {
    super.connectedCallback();
    this._loadPeers();
    this._pollTimer = setInterval(() => this._loadPeers(), 10000);
  }

  disconnectedCallback(): void {
    super.disconnectedCallback();
    if (this._pollTimer) { clearInterval(this._pollTimer); this._pollTimer = null; }
  }

  private async _loadPeers(): Promise<void> {
    try {
      const r = await fetchWithAuth('/api/peers');
      if (!r.ok) return;
      this._peers = await r.json();
      this.peerTerminalModes = {};
      this._peers.forEach(p => {
        this.peerTerminalModes[p.url] = { id: p.id, terminalMode: p.terminalMode };
      });
    } catch { /* ignore */ }
  }

  private _healthVariant(health: string): 'success' | 'warning' | 'danger' {
    if (health === 'UP') return 'success';
    if (health === 'DOWN') return 'danger';
    return 'warning';
  }

  private _circuitVariant(state: string): 'success' | 'warning' | 'danger' {
    if (state === 'CLOSED') return 'success';
    if (state === 'OPEN') return 'danger';
    return 'warning';
  }

  private _circuitLabel(state: string): string {
    return state === 'HALF_OPEN' ? 'half-open' : state.toLowerCase();
  }

  private _timeAgo(iso?: string): string {
    if (!iso) return 'never';
    const m = Math.floor((Date.now() - new Date(iso).getTime()) / 60000);
    if (m < 1) return 'just now';
    if (m < 60) return m + 'm ago';
    const h = Math.floor(m / 60);
    if (h < 24) return h + 'h ago';
    return Math.floor(h / 24) + 'd ago';
  }

  private async _pingPeer(id: string): Promise<void> {
    await fetchWithAuth('/api/peers/' + id + '/ping', { method: 'POST' });
    setTimeout(() => this._loadPeers(), 2000);
  }

  private async _toggleMode(p: Peer): Promise<void> {
    const newMode = p.terminalMode === 'DIRECT' ? 'PROXY' : 'DIRECT';
    await fetchWithAuth('/api/peers/' + p.id, {
      method: 'PATCH', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ terminalMode: newMode }),
    });
    this._loadPeers();
  }

  private async _removePeer(p: Peer): Promise<void> {
    if (!confirm('Remove peer "' + (p.name || p.url) + '"?')) return;
    await fetchWithAuth('/api/peers/' + p.id, { method: 'DELETE' });
    this._loadPeers();
  }

  private async _addPeer(): Promise<void> {
    await fetchWithAuth('/api/peers', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url: this._addUrl, name: this._addName || null, terminalMode: this._addMode }),
    });
    this._showAddDialog = false;
    this._addUrl = ''; this._addName = ''; this._addMode = 'DIRECT';
    this._loadPeers();
  }

  override render() {
    return html`
      <div class="header">
        <span class="title">Fleet</span>
        <pages-button size="xs" variant="ghost" label="+ Add Peer"
          @click=${() => { this._showAddDialog = true; }}></pages-button>
      </div>
      ${this._peers.length === 0
        ? html`<div class="peer-empty">No peers configured</div>`
        : this._peers.map(p => this._renderPeer(p))}

      <pages-modal .open=${this._showAddDialog}
        @pages-modal-close=${() => { this._showAddDialog = false; }}>
        <span slot="header">Add Peer</span>
        <div class="form-field">
          <pages-input label="URL" placeholder="http://mac-mini:7777" required
            @input=${(e: Event) => { this._addUrl = (e.target as any).value; }}></pages-input>
        </div>
        <div class="form-field">
          <pages-input label="Name" placeholder="Mac Mini (optional)"
            @input=${(e: Event) => { this._addName = (e.target as any).value; }}></pages-input>
        </div>
        <div class="form-field">
          <pages-select label="Terminal Mode" .options=${this._modeOptions} value="DIRECT"
            @change=${(e: Event) => { this._addMode = (e.target as any).value; }}></pages-select>
        </div>
        <div slot="actions">
          <pages-button variant="ghost" label="Cancel"
            @click=${() => { this._showAddDialog = false; }}></pages-button>
          <pages-button variant="primary" label="Add Peer"
            @click=${() => this._addPeer()}></pages-button>
        </div>
      </pages-modal>
    `;
  }

  private _renderPeer(p: Peer) {
    const staleNote = p.health === 'DOWN' && p.sessionCount > 0;
    return html`
      <div class="peer-card">
        <div class="peer-header">
          <pages-status-dot variant=${this._healthVariant(p.health)}></pages-status-dot>
          <span class="peer-name">${p.name || p.url}</span>
          <span class="peer-source">${p.source.toLowerCase()}</span>
        </div>
        <div class="peer-url">${p.url}</div>
        <div class="peer-meta">
          <pages-badge label=${this._circuitLabel(p.circuitState)}
            variant=${this._circuitVariant(p.circuitState)} size="sm"></pages-badge>
          ${staleNote
            ? html`<pages-badge label=${'⏰ ' + this._timeAgo(p.lastSeen)} variant="warning" size="sm"></pages-badge>`
            : html`<span>${this._timeAgo(p.lastSeen)}</span>`}
        </div>
        <div class="peer-actions">
          <pages-button size="xs" variant="ghost" label="Ping"
            @click=${() => this._pingPeer(p.id)}></pages-button>
          <pages-button size="xs" variant="ghost" label=${p.terminalMode}
            title="Click to toggle" @click=${() => this._toggleMode(p)}></pages-button>
          ${p.source !== 'CONFIG' ? html`
            <pages-button size="xs" variant="danger" label="Remove"
              @click=${() => this._removePeer(p)}></pages-button>
          ` : nothing}
        </div>
      </div>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'claudony-fleet-panel': ClaudonyFleetPanel;
  }
}
