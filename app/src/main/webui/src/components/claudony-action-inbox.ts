import { LitElement, html, css, nothing } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import { fetchWithAuth } from '../util/auth';
import { timeAgo } from '../util/time';

interface ActionDescriptor {
  name: string;
  label: string;
  method: string;
  endpoint: string;
}

interface ActionItem {
  id: string;
  sourceType: 'COMMITMENT' | 'STALL' | 'OVERSIGHT' | 'WORKITEM';
  urgency: 'HIGH' | 'MEDIUM' | 'LOW';
  title: string;
  status: string;
  actionable: boolean;
  caseId?: string;
  channelName?: string;
  createdAt: string;
  actions: ActionDescriptor[];
}

interface ActionCounts { high: number; medium: number; low: number; }
interface ActionInboxResponse { items: ActionItem[]; counts: ActionCounts; }

const URGENCY_LABEL: Record<string, string> = { HIGH: '\u{1F534}', MEDIUM: '\u{1F7E1}', LOW: '⚪' };
const SOURCE_LABEL: Record<string, string> = {
  COMMITMENT: '\u{1F4CB}', STALL: '⚠️', OVERSIGHT: '\u{1F441}️', WORKITEM: '\u{1F4DD}',
};

@customElement('claudony-action-inbox')
export class ClaudonyActionInbox extends LitElement {
  @state() private items: ActionItem[] = [];
  @state() private counts: ActionCounts = { high: 0, medium: 0, low: 0 };
  @state() private loading = true;
  @state() private error: string | null = null;

  static override styles = css`
    :host { display: block; height: 100%; overflow-y: auto; padding: 12px; }
    .summary { display: flex; gap: 16px; padding: 8px 0; font-size: 0.875rem;
               border-bottom: 1px solid var(--pages-neutral-5, #333); margin-bottom: 8px; }
    .summary .count { font-weight: 600; }
    table { width: 100%; border-collapse: collapse; font-size: 0.8125rem; }
    th { text-align: left; padding: 6px 8px; border-bottom: 1px solid var(--pages-neutral-5, #333);
         color: var(--pages-neutral-9, #999); font-weight: 500; }
    td { padding: 8px; border-bottom: 1px solid var(--pages-neutral-4, #222); }
    tr:hover { background: var(--pages-neutral-4, #1a1a1a); }
    .icon { width: 30px; text-align: center; }
    .actions { display: flex; gap: 4px; }
    .actions button { padding: 2px 8px; border: 1px solid var(--pages-neutral-5, #555);
                      background: var(--pages-neutral-3, #222); color: inherit;
                      border-radius: 3px; cursor: pointer; font-size: 0.75rem; }
    .actions button:hover { background: var(--pages-accent-9, #0066cc); border-color: var(--pages-accent-9); }
    .empty { padding: 32px; text-align: center; color: var(--pages-neutral-9, #666); }
    .error { padding: 12px; color: var(--pages-danger-9, #cc3333); }
  `;

  override connectedCallback(): void {
    super.connectedCallback();
    this._fetchActions();
  }

  private async _fetchActions(): Promise<void> {
    this.loading = true;
    try {
      const res = await fetchWithAuth('/api/actions');
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data: ActionInboxResponse = await res.json();
      this.items = data.items;
      this.counts = data.counts;
      this.error = null;
    } catch (e: any) {
      this.error = e.message;
    } finally {
      this.loading = false;
    }
  }

  override render() {
    if (this.error) return html`<div class="error">Error: ${this.error}</div>`;
    if (this.loading) return html`<div class="empty">Loading...</div>`;
    return html`
      ${this._renderSummary()}
      ${this.items.length === 0
        ? html`<div class="empty">No actions pending</div>`
        : this._renderTable()}
    `;
  }

  private _renderSummary() {
    return html`
      <div class="summary">
        <span>${URGENCY_LABEL.HIGH} <span class="count">${this.counts.high}</span> urgent</span>
        <span>${URGENCY_LABEL.MEDIUM} <span class="count">${this.counts.medium}</span> pending</span>
        <span>${this.items.length} total</span>
      </div>
    `;
  }

  private _renderTable() {
    return html`
      <table>
        <thead><tr>
          <th class="icon"></th><th class="icon"></th>
          <th>Title</th><th>Status</th><th>Age</th><th>Actions</th>
        </tr></thead>
        <tbody>
          ${this.items.map(item => html`
            <tr>
              <td class="icon">${URGENCY_LABEL[item.urgency] ?? ''}</td>
              <td class="icon">${SOURCE_LABEL[item.sourceType] ?? ''}</td>
              <td>${item.title}</td>
              <td>${item.status}</td>
              <td>${timeAgo(item.createdAt)}</td>
              <td class="actions">
                ${item.actions.map(a => html`
                  <button @click=${() => this._executeAction(a)}>${a.label}</button>
                `)}
              </td>
            </tr>
          `)}
        </tbody>
      </table>
    `;
  }

  private async _executeAction(action: ActionDescriptor): Promise<void> {
    if (action.method === 'GET') {
      window.location.href = action.endpoint;
      return;
    }
    try {
      await fetchWithAuth(action.endpoint, { method: action.method });
      await this._fetchActions();
    } catch (e) {
      console.error('Action failed', e);
    }
  }
}
