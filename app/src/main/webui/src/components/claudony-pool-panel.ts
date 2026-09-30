import { LitElement, html, css, nothing } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import { fetchWithAuth } from '../util/auth.js';
import { timeAgo } from '../util/time.js';

interface PoolSummary {
  name: string;
  status: { min: number; max: number; active: number; idle: number; total: number; health: string };
  scalingType: string;
}

interface PoolDetail {
  name: string;
  status: { min: number; max: number; active: number; idle: number; total: number; health: string };
  definition: { agent: { name: string; workingDir: string }; pool: { minActive: number; maxActive: number; eviction: string } } | null;
  scaling: { type: string; lastDecision: { direction: string; count: number; reason: string; timestamp: string } | null; cooldownRemaining: string } | null;
  demand: { acquires: number; evictions: number; exhaustions: number } | null;
}

interface SessionInfo {
  instanceId: string;
  identity: string;
  workingDir: string;
  state: string;
  idleSeconds: number;
  memoryBytes: number;
}

@customElement('claudony-pool-panel')
export class ClaudonyPoolPanel extends LitElement {
  @state() private _pools: PoolSummary[] = [];
  @state() private _selectedPool = '';
  @state() private _detail: PoolDetail | null = null;
  @state() private _sessions: SessionInfo[] = [];
  private _pollTimer: ReturnType<typeof setInterval> | null = null;

  static override styles = css`
    :host {
      display: flex;
      height: 100%;
      font-family: var(--pages-font-family);
      color: var(--pages-neutral-11);
    }
    .sidebar {
      width: 220px;
      border-right: 1px solid var(--pages-neutral-4);
      overflow-y: auto;
      padding: 8px;
    }
    .sidebar h3 {
      margin: 0 0 12px;
      font-size: var(--pages-font-size-sm);
      color: var(--pages-neutral-8);
      text-transform: uppercase;
      letter-spacing: 0.05em;
    }
    .pool-item {
      padding: 8px 12px;
      cursor: pointer;
      border-radius: var(--pages-radius-md);
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .pool-item:hover { background: var(--pages-neutral-2); }
    .pool-item.selected { background: var(--pages-neutral-3); }
    .pool-count {
      margin-left: auto;
      font-size: var(--pages-font-size-xs);
      color: var(--pages-neutral-8);
    }
    .detail {
      flex: 1;
      overflow-y: auto;
      padding: 16px;
    }
    .status-header {
      display: flex;
      gap: 16px;
      align-items: center;
      margin-bottom: 16px;
    }
    .status-header h2 { margin: 0; }
    .capacity-bar {
      flex: 1;
      height: 8px;
      background: var(--pages-neutral-3);
      border-radius: var(--pages-radius-md);
      overflow: hidden;
    }
    .capacity-fill {
      height: 100%;
      background: var(--pages-accent-9);
      transition: width 0.3s;
    }
    .kpi {
      display: flex;
      gap: 16px;
      margin-bottom: 16px;
    }
    .kpi-card {
      padding: 12px 16px;
      background: var(--pages-neutral-2);
      border-radius: var(--pages-radius-md);
      min-width: 80px;
    }
    .kpi-value {
      font-size: var(--pages-font-size-2xl);
      font-weight: 600;
    }
    .kpi-label {
      font-size: var(--pages-font-size-xs);
      color: var(--pages-neutral-8);
    }
    table {
      width: 100%;
      border-collapse: collapse;
      margin-top: 12px;
    }
    th, td {
      text-align: left;
      padding: 8px 12px;
      border-bottom: 1px solid var(--pages-neutral-3);
    }
    th {
      color: var(--pages-neutral-8);
      font-size: var(--pages-font-size-xs);
      text-transform: uppercase;
      letter-spacing: 0.05em;
    }
    .action-btn {
      background: none;
      border: 1px solid var(--pages-neutral-5);
      color: var(--pages-neutral-11);
      padding: 4px 8px;
      border-radius: var(--pages-radius-md);
      cursor: pointer;
      font-size: var(--pages-font-size-xs);
      margin-right: 4px;
    }
    .action-btn:hover { border-color: var(--pages-accent-9); }
    .scaling-section {
      margin-top: 16px;
      padding: 12px;
      background: var(--pages-neutral-2);
      border-radius: var(--pages-radius-md);
    }
    .scaling-section h3 { margin-top: 0; }
    .chart-placeholder {
      flex: 1;
      height: 200px;
      background: var(--pages-neutral-2);
      border-radius: var(--pages-radius-md);
      display: flex;
      align-items: center;
      justify-content: center;
      color: var(--pages-neutral-6);
    }
    .event-log {
      max-height: 300px;
      overflow-y: auto;
      background: var(--pages-neutral-2);
      border-radius: var(--pages-radius-md);
      padding: 12px;
    }
    .health-dot {
      width: 8px;
      height: 8px;
      border-radius: 50%;
      display: inline-block;
      flex-shrink: 0;
    }
    .health-dot.HEALTHY { background: var(--pages-success-9); }
    .health-dot.DEGRADED { background: var(--pages-warning-9); }
    .health-dot.UNHEALTHY { background: var(--pages-danger-9); }
    .empty-state {
      text-align: center;
      color: var(--pages-neutral-6);
      padding: 24px;
    }
  `;

  override connectedCallback() {
    super.connectedCallback();
    this._fetchPools();
    this._pollTimer = setInterval(() => this._fetchPools(), 10000);
  }

  override disconnectedCallback() {
    super.disconnectedCallback();
    if (this._pollTimer) clearInterval(this._pollTimer);
  }

  private async _fetchPools() {
    try {
      const resp = await fetchWithAuth('/api/pools');
      if (resp.ok) {
        this._pools = await resp.json();
        if (!this._selectedPool && this._pools.length > 0) {
          this._selectedPool = this._pools[0].name;
        }
        if (this._selectedPool) this._fetchDetail();
      }
    } catch (e) { console.error('Failed to fetch pools', e); }
  }

  private async _fetchDetail() {
    try {
      const [detailResp, sessionsResp] = await Promise.all([
        fetchWithAuth(`/api/pools/${this._selectedPool}`),
        fetchWithAuth(`/api/pools/${this._selectedPool}/sessions`),
      ]);
      if (detailResp.ok) this._detail = await detailResp.json();
      if (sessionsResp.ok) this._sessions = await sessionsResp.json();
    } catch (e) { console.error('Failed to fetch pool detail', e); }
  }

  private async _suspendSession(id: string) {
    await fetchWithAuth(`/api/pools/${this._selectedPool}/sessions/${id}/suspend`, { method: 'POST' });
    this._fetchDetail();
  }

  private async _resumeSession(id: string) {
    await fetchWithAuth(`/api/pools/${this._selectedPool}/sessions/${id}/resume`, { method: 'POST' });
    this._fetchDetail();
  }

  private async _destroySession(id: string) {
    await fetchWithAuth(`/api/pools/${this._selectedPool}/sessions/${id}`, { method: 'DELETE' });
    this._fetchDetail();
  }

  override render() {
    return html`
      <div class="sidebar">
        <h3>Pools</h3>
        ${this._pools.map(p => html`
          <div class="pool-item ${p.name === this._selectedPool ? 'selected' : ''}"
               @click=${() => { this._selectedPool = p.name; this._fetchDetail(); }}>
            <span class="health-dot ${p.status.health}"></span>
            <span>${p.name}</span>
            <span class="pool-count">${p.status.active}/${p.status.max}</span>
          </div>
        `)}
      </div>
      <div class="detail">
        ${this._detail ? this._renderDetail() : html`<div class="empty-state">Select a pool</div>`}
      </div>
    `;
  }

  private _renderDetail() {
    const d = this._detail!;
    const fillPct = d.status.max > 0 ? (d.status.active / d.status.max) * 100 : 0;
    return html`
      <div class="status-header">
        <span class="health-dot ${d.status.health}"></span>
        <h2>${d.name}</h2>
        <div class="capacity-bar">
          <div class="capacity-fill" style="width: ${fillPct}%"></div>
        </div>
        <span>${d.status.active}/${d.status.max}</span>
      </div>
      <div class="kpi">
        <div class="kpi-card"><div class="kpi-value">${d.status.active}</div><div class="kpi-label">Active</div></div>
        <div class="kpi-card"><div class="kpi-value">${d.status.idle}</div><div class="kpi-label">Idle</div></div>
        <div class="kpi-card"><div class="kpi-value">${d.status.min}</div><div class="kpi-label">Min</div></div>
        <div class="kpi-card"><div class="kpi-value">${d.status.max}</div><div class="kpi-label">Max</div></div>
      </div>
      ${this._renderSessions()}
      ${this._renderScaling()}
      ${this._renderCharts()}
      ${this._renderEventLog()}
    `;
  }

  private _renderSessions() {
    return html`
      <h3>Sessions</h3>
      <table>
        <thead><tr><th>Identity</th><th>Working Dir</th><th>State</th><th>Idle</th><th>Memory</th><th>Actions</th></tr></thead>
        <tbody>
          ${this._sessions.map(s => html`
            <tr>
              <td>${s.identity}</td>
              <td>${s.workingDir}</td>
              <td>${s.state}</td>
              <td>${s.idleSeconds}s</td>
              <td>${Math.round(s.memoryBytes / 1048576)}MB</td>
              <td>
                ${s.state === 'ACTIVE' ? html`<button class="action-btn" @click=${() => this._suspendSession(s.instanceId)}>Suspend</button>` : nothing}
                ${s.state === 'SUSPENDED' ? html`<button class="action-btn" @click=${() => this._resumeSession(s.instanceId)}>Resume</button>` : nothing}
                <button class="action-btn" @click=${() => this._destroySession(s.instanceId)}>Destroy</button>
              </td>
            </tr>
          `)}
          ${this._sessions.length === 0 ? html`<tr><td colspan="6" class="empty-state">No sessions</td></tr>` : nothing}
        </tbody>
      </table>
    `;
  }

  private _renderScaling() {
    const s = this._detail?.scaling;
    if (!s) return nothing;
    return html`
      <div class="scaling-section">
        <h3>Scaling</h3>
        <p>Policy: <strong>${s.type}</strong> | Cooldown: ${s.cooldownRemaining}</p>
        ${s.lastDecision ? html`
          <p>Last decision: <strong>${s.lastDecision.direction}</strong>
            ${s.lastDecision.count > 0 ? `+${s.lastDecision.count}` : s.lastDecision.count}
            — ${s.lastDecision.reason}
            (${s.lastDecision.timestamp ? timeAgo(s.lastDecision.timestamp) : 'unknown'})</p>
        ` : html`<p>No scaling decisions yet</p>`}
      </div>
    `;
  }

  private _renderCharts() {
    return html`
      <h3>Metrics</h3>
      <div style="display: flex; gap: 16px; margin-bottom: 16px;">
        <div class="chart-placeholder">Fill Ratio (requires IoTDB)</div>
        <div class="chart-placeholder">Demand Metrics (requires IoTDB)</div>
      </div>
    `;
  }

  private _renderEventLog() {
    return html`
      <h3>Event Log</h3>
      <div class="event-log">
        <div class="empty-state">Event log available when EventBroadcaster is connected</div>
      </div>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'claudony-pool-panel': ClaudonyPoolPanel;
  }
}
