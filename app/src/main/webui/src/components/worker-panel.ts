import { LitElement, html, css, nothing } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import '@casehubio/pages-ui-components';

interface WorkerInfo {
  id: string;
  name: string;
  status: string;
  roleName?: string;
  createdAt: string;
}

@customElement('claudony-worker-panel')
export class ClaudonyWorkerPanel extends LitElement {
  @state() private _workers: WorkerInfo[] = [];
  @state() private _collapsed = true;

  private _sessionId = '';
  private _eventSource: EventSource | null = null;

  static override styles = css`
    :host {
      width: 240px; min-width: 240px;
      background: var(--pages-neutral-2); border-right: 1px solid var(--pages-neutral-4);
      display: flex; flex-direction: column;
      transition: width 0.2s, min-width 0.2s; overflow: hidden;
    }
    :host(.collapsed) { width: 0; min-width: 0; }
    .panel-header {
      display: flex; align-items: center; justify-content: space-between;
      padding: 8px 12px; border-bottom: 1px solid var(--pages-neutral-4); flex-shrink: 0;
    }
    .panel-title {
      font-size: 12px; font-weight: 600; text-transform: uppercase;
      color: var(--pages-neutral-8);
    }
    .worker-list { flex: 1; overflow-y: auto; padding: 8px 0; }
    .worker-row {
      display: flex; align-items: center; gap: 8px;
      padding: 6px 12px; cursor: pointer; font-size: 13px;
    }
    .worker-row:hover { background: rgba(255,255,255,0.04); }
    .worker-row.active-worker {
      background: var(--pages-accent-3); border-left: 2px solid var(--pages-accent-9);
    }
    .worker-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .worker-time { font-size: 11px; color: var(--pages-neutral-8); }
    .placeholder {
      padding: 16px; color: var(--pages-neutral-8);
      font-size: 13px; text-align: center;
    }
  `;

  configure(opts: { sessionId: string }): void {
    this._sessionId = opts.sessionId;
    if (this._eventSource) { this._eventSource.close(); this._eventSource = null; }
    if (!this._collapsed) this._connectSSE();
  }

  destroy(): void {
    if (this._eventSource) { this._eventSource.close(); this._eventSource = null; }
  }

  open(): void {
    this._collapsed = false;
    this.classList.remove('collapsed');
    if (this._sessionId && !this._eventSource) this._connectSSE();
  }

  close(): void {
    this._collapsed = true;
    this.classList.add('collapsed');
    if (this._eventSource) { this._eventSource.close(); this._eventSource = null; }
  }

  toggle(): void { this._collapsed ? this.open() : this.close(); }

  connectedCallback(): void {
    super.connectedCallback();
    this.classList.add('collapsed');
  }

  private _connectSSE(): void {
    if (this._eventSource) this._eventSource.close();
    this._eventSource = new EventSource('/api/sessions/' + this._sessionId + '/case-events');
    this._eventSource.onmessage = (e) => { this._workers = JSON.parse(e.data) as WorkerInfo[]; };
    if ((window as unknown as Record<string, unknown>).__CLAUDONY_TEST_MODE__) {
      (window as unknown as Record<string, unknown>)._caseEventSource = this._eventSource;
    }
  }

  private _statusVariant(status: string): string {
    switch (status.toLowerCase()) {
      case 'active': return 'success';
      case 'waiting': return 'warning';
      case 'faulted': return 'danger';
      default: return 'neutral';
    }
  }

  private _timeAgo(iso: string): string {
    const m = Math.floor((Date.now() - new Date(iso).getTime()) / 60000);
    if (m < 1) return 'now';
    if (m < 60) return m + 'm';
    return Math.floor(m / 60) + 'h';
  }

  override render() {
    return html`
      <div class="panel-header">
        <span class="panel-title">Workers</span>
        <pages-button variant="ghost" size="xs" label="×"
          title="Close" @click=${() => this.close()}></pages-button>
      </div>
      <div class="worker-list">
        ${this._workers.length === 0
          ? html`<div class="placeholder">No case assigned.</div>`
          : this._workers.map(w => {
              const status = (w.status || 'idle').toLowerCase();
              const isActive = w.id === this._sessionId;
              const name = w.roleName || w.name.replace(/^claudony-worker-/, '').replace(/^claudony-/, '');
              return html`
                <div class="worker-row ${isActive ? 'active-worker' : ''}"
                  @click=${() => { if (!isActive) this._dispatchWorkerSelect(w.id, name); }}>
                  <pages-status-dot variant=${this._statusVariant(status)}></pages-status-dot>
                  <span class="worker-name">${name}</span>
                  <span class="worker-time">${this._timeAgo(w.createdAt)}</span>
                </div>`;
            })}
      </div>
    `;
  }

  private _dispatchWorkerSelect(sessionId: string, name: string): void {
    this.dispatchEvent(new CustomEvent('pages-event', {
      bubbles: true, composed: true,
      detail: { topic: 'worker-selected', payload: { sessionId, name } },
    }));
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'claudony-worker-panel': ClaudonyWorkerPanel;
  }
}
