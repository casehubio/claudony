import { LitElement, html, css, nothing } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import { fetchWithAuth } from '../util/auth.js';
import { timeAgo } from '../util/time.js';

interface PoolSummary {
  name: string;
  status: { min: number; max: number; active: number; idle: number; total: number; health: string };
  scalingType: string;
}

interface ScalingConfigView {
  targetFillRatio: number | null;
  steps: Array<{ threshold: number; adjustment: number }> | null;
  exhaustionThreshold: number | null;
  latencyThresholdMs: number | null;
  beanName: string | null;
  cooldown: string;
  scaleInCooldown: string;
}

interface PoolDetail {
  name: string;
  status: { min: number; max: number; active: number; idle: number; total: number; health: string };
  definition: { agent: { name: string; workingDir: string }; pool: { minActive: number; maxActive: number; eviction: string } } | null;
  scaling: { type: string; config: ScalingConfigView | null; lastDecision: { direction: string; count: number; reason: string; timestamp: string } | null; cooldownRemaining: string } | null;
  demand: { acquires: number; evictions: number; exhaustions: number } | null;
  budget: { currentCostUsd: number; costLimit: number | null; currentTokens: number; tokenLimit: number | null; windowRemaining: string; enforcement: string; status: string } | null;
}

interface SessionInfo {
  instanceId: string;
  identity: string;
  workingDir: string;
  state: string;
  idleSeconds: number;
  memoryBytes: number;
}

interface PoolEvent {
  type: string;
  timestamp?: string;
  [k: string]: unknown;
}

@customElement('claudony-pool-panel')
export class ClaudonyPoolPanel extends LitElement {
  @state() private _pools: PoolSummary[] = [];
  @state() private _selectedPool = '';
  @state() private _detail: PoolDetail | null = null;
  @state() private _sessions: SessionInfo[] = [];
  @state() private _events: PoolEvent[] = [];
  @state() private _editingScaling = false;
  @state() private _scalingForm: Record<string, unknown> = {};
  private _eventSource: EventSource | null = null;
  private _fallbackTimer: ReturnType<typeof setInterval> | null = null;

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
    .scaling-editor label {
      display: block;
      margin: 8px 0;
      font-size: var(--pages-font-size-sm);
    }
    .scaling-editor input, .scaling-editor select {
      background: var(--pages-neutral-3);
      border: 1px solid var(--pages-neutral-5);
      color: var(--pages-neutral-11);
      border-radius: var(--pages-radius-md);
      padding: 4px 8px;
      font-size: var(--pages-font-size-sm);
      margin-left: 8px;
    }
    .scaling-editor select { min-width: 150px; }
    .kpi-input {
      width: 60px;
      font-size: var(--pages-font-size-2xl);
      font-weight: 600;
      background: var(--pages-neutral-3);
      border: 1px solid var(--pages-neutral-5);
      color: var(--pages-neutral-11);
      border-radius: var(--pages-radius-md);
      padding: 2px 4px;
      text-align: center;
    }
    .type-badge {
      padding: 2px 6px;
      border-radius: var(--pages-radius-md);
      background: var(--pages-neutral-3);
      font-size: var(--pages-font-size-xs);
    }
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
    .budget-bar {
      width: 100%;
      height: 4px;
      background: var(--pages-neutral-3);
      border-radius: 2px;
      margin: 4px 0;
    }
    .budget-fill {
      height: 100%;
      border-radius: 2px;
      background: var(--pages-success-9);
      transition: width 0.3s;
    }
    .budget-fill.warning { background: var(--pages-warning-9); }
    .budget-fill.exceeded { background: var(--pages-danger-9); }
    .badge {
      font-size: var(--pages-font-size-xs);
      padding: 2px 6px;
      border-radius: var(--pages-radius-md);
      background: var(--pages-success-3);
      color: var(--pages-success-11);
      margin-left: 8px;
    }
    .badge.exceeded {
      background: var(--pages-danger-3);
      color: var(--pages-danger-11);
    }
  `;

  override connectedCallback() {
    super.connectedCallback();
    this._fetchPools();
  }

  override disconnectedCallback() {
    super.disconnectedCallback();
    if (this._eventSource) { this._eventSource.close(); this._eventSource = null; }
    if (this._fallbackTimer) clearInterval(this._fallbackTimer);
  }

  private _connectSSE() {
    if (this._eventSource) { this._eventSource.close(); this._eventSource = null; }
    if (!this._selectedPool) return;
    this._eventSource = new EventSource(`/api/pool-events/${this._selectedPool}`);
    this._eventSource.onmessage = (e) => {
      try {
        const data = JSON.parse(e.data);
        if (data.detail) {
          this._detail = data.detail;
          this._sessions = data.sessions || [];
        } else {
          this._events = [data, ...this._events].slice(0, 50);
          if (data.type === 'scaling' && this._detail?.scaling) {
            this._detail = { ...this._detail, scaling: { ...this._detail.scaling, lastDecision: { direction: data.direction, count: data.count, reason: data.reason, timestamp: data.timestamp } } };
          }
          if (data.type === 'session') this._fetchDetail();
          if (data.type === 'budget') this._fetchDetail();
        }
      } catch (err) { console.error('SSE parse error', err); }
    };
    this._eventSource.onerror = () => {
      if (this._eventSource) { this._eventSource.close(); this._eventSource = null; }
      if (!this._fallbackTimer) { this._fallbackTimer = setInterval(() => this._fetchPools(), 60000); }
    };
    if (this._fallbackTimer) { clearInterval(this._fallbackTimer); this._fallbackTimer = null; }
  }

  private async _fetchPools() {
    try {
      const resp = await fetchWithAuth('/api/claudony/pools');
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
        fetchWithAuth(`/api/claudony/pools/${this._selectedPool}`),
        fetchWithAuth(`/api/claudony/pools/${this._selectedPool}/sessions`),
      ]);
      if (detailResp.ok) this._detail = await detailResp.json();
      if (sessionsResp.ok) this._sessions = await sessionsResp.json();
    } catch (e) { console.error('Failed to fetch pool detail', e); }
  }

  private _selectPool(name: string) {
    this._selectedPool = name;
    this._events = [];
    this._editingScaling = false;
    this._scalingForm = {};
    this._fetchDetail();
    this._connectSSE();
  }

  private async _updatePool(update: Record<string, unknown>) {
    try {
      const resp = await fetchWithAuth(`/api/claudony/pools/${this._selectedPool}/update`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(update),
      });
      if (resp.ok) { this._detail = await resp.json(); this._fetchPools(); }
      else { console.error('Update failed:', await resp.text()); }
    } catch (e) { console.error('Update failed', e); }
  }

  private async _suspendSession(id: string) {
    await fetchWithAuth(`/api/claudony/pools/${this._selectedPool}/sessions/${id}/suspend`, { method: 'POST' });
    this._fetchDetail();
  }

  private async _resumeSession(id: string) {
    await fetchWithAuth(`/api/claudony/pools/${this._selectedPool}/sessions/${id}/resume`, { method: 'POST' });
    this._fetchDetail();
  }

  private async _destroySession(id: string) {
    await fetchWithAuth(`/api/claudony/pools/${this._selectedPool}/sessions/${id}/destroy`, { method: 'POST' });
    this._fetchDetail();
  }

  override render() {
    return html`
      <div class="sidebar">
        <h3>Pools</h3>
        ${this._pools.map(p => html`
          <div class="pool-item ${p.name === this._selectedPool ? 'selected' : ''}"
               @click=${() => this._selectPool(p.name)}>
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
        <div class="kpi-card">
          <input class="kpi-input" type="number" min="0" .value=${String(d.status.min)}
                 @change=${(e: Event) => this._updatePool({ minActive: +(e.target as HTMLInputElement).value })} />
          <div class="kpi-label">Min</div>
        </div>
        <div class="kpi-card">
          <input class="kpi-input" type="number" min="1" .value=${String(d.status.max)}
                 @change=${(e: Event) => this._updatePool({ maxActive: +(e.target as HTMLInputElement).value })} />
          <div class="kpi-label">Max</div>
        </div>
      </div>
      ${this._renderBudget()}
      ${this._renderSessions()}
      ${this._renderScaling()}
      ${this._renderEventLog()}
    `;
  }

  private _renderBudget() {
    const b = this._detail?.budget;
    if (!b) return nothing;
    const costPct = b.costLimit ? Math.min((b.currentCostUsd / b.costLimit) * 100, 100) : 0;
    const tokenPct = b.tokenLimit ? Math.min((b.currentTokens / b.tokenLimit) * 100, 100) : 0;
    const barClass = (pct: number) => pct >= 100 ? 'exceeded' : pct >= 80 ? 'warning' : '';
    const formatTokens = (n: number) => n >= 1_000_000 ? `${(n / 1_000_000).toFixed(1)}M` : n >= 1_000 ? `${(n / 1_000).toFixed(0)}K` : String(n);
    return html`
      <h3>Budget <span class="badge ${b.status === 'EXCEEDED' ? 'exceeded' : ''}">${b.status}</span></h3>
      <div class="kpi">
        <div class="kpi-card">
          <div class="kpi-value">$${b.currentCostUsd.toFixed(2)}${b.costLimit ? ` / $${b.costLimit.toFixed(2)}` : ''}</div>
          <div class="budget-bar"><div class="budget-fill ${barClass(costPct)}" style="width: ${costPct}%"></div></div>
          <div class="kpi-label">Cost</div>
        </div>
        <div class="kpi-card">
          <div class="kpi-value">${formatTokens(b.currentTokens)}${b.tokenLimit ? ` / ${formatTokens(b.tokenLimit)}` : ''}</div>
          <div class="budget-bar"><div class="budget-fill ${barClass(tokenPct)}" style="width: ${tokenPct}%"></div></div>
          <div class="kpi-label">Tokens</div>
        </div>
        <div class="kpi-card"><div class="kpi-value">${b.windowRemaining}</div><div class="kpi-label">Window</div></div>
        <div class="kpi-card"><div class="kpi-value">${b.enforcement.replace('_', ' ').toLowerCase()}</div><div class="kpi-label">Enforcement</div></div>
      </div>
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
        <h3>Scaling
          <button class="action-btn" style="margin-left: 8px;" @click=${() => {
            this._editingScaling = !this._editingScaling;
            if (this._editingScaling) {
              this._scalingForm = {
                scalingType: s.type,
                targetFillRatio: s.config?.targetFillRatio ?? 0.7,
                exhaustionThreshold: s.config?.exhaustionThreshold ?? 5,
                latencyThresholdMs: s.config?.latencyThresholdMs ?? 500,
                cooldown: s.config?.cooldown ?? '60s',
                scaleInCooldown: s.config?.scaleInCooldown ?? '120s',
              };
            }
          }}>${this._editingScaling ? 'Cancel' : 'Edit'}</button>
        </h3>
        ${this._editingScaling ? this._renderScalingEditor() : html`
          <p>Policy: <strong>${s.type}</strong> | Cooldown: ${s.cooldownRemaining}</p>
          ${s.lastDecision ? html`
            <p>Last decision: <strong>${s.lastDecision.direction}</strong>
              ${s.lastDecision.count > 0 ? `+${s.lastDecision.count}` : s.lastDecision.count}
              — ${s.lastDecision.reason}
              (${s.lastDecision.timestamp ? timeAgo(s.lastDecision.timestamp) : 'unknown'})</p>
          ` : html`<p>No scaling decisions yet</p>`}
        `}
      </div>
    `;
  }

  private _renderScalingEditor() {
    const type = this._scalingForm.scalingType as string || 'none';
    return html`
      <div class="scaling-editor">
        <label>Type:
          <select @change=${(e: Event) => { this._scalingForm = { ...this._scalingForm, scalingType: (e.target as HTMLSelectElement).value }; this.requestUpdate(); }}>
            <option value="none" ?selected=${type === 'none'}>None</option>
            <option value="target-tracking" ?selected=${type === 'target-tracking'}>Target Tracking</option>
            <option value="step" ?selected=${type === 'step'}>Step</option>
            <option value="demand-pressure" ?selected=${type === 'demand-pressure'}>Demand Pressure</option>
          </select>
        </label>
        ${type === 'target-tracking' ? html`
          <label>Fill Ratio:
            <input type="range" min="0.1" max="1.0" step="0.05"
              .value=${String(this._scalingForm.targetFillRatio ?? 0.7)}
              @input=${(e: Event) => { this._scalingForm = { ...this._scalingForm, targetFillRatio: +(e.target as HTMLInputElement).value }; this.requestUpdate(); }}
            /> ${this._scalingForm.targetFillRatio ?? 0.7}
          </label>
        ` : nothing}
        ${type === 'demand-pressure' ? html`
          <label>Exhaustion Threshold:
            <input type="number" min="0"
              .value=${String(this._scalingForm.exhaustionThreshold ?? 5)}
              @change=${(e: Event) => { this._scalingForm = { ...this._scalingForm, exhaustionThreshold: +(e.target as HTMLInputElement).value }; }}
            />
          </label>
          <label>Latency Threshold (ms):
            <input type="number" min="1"
              .value=${String(this._scalingForm.latencyThresholdMs ?? 500)}
              @change=${(e: Event) => { this._scalingForm = { ...this._scalingForm, latencyThresholdMs: +(e.target as HTMLInputElement).value }; }}
            />
          </label>
        ` : nothing}
        <label>Cooldown:
          <input type="text" placeholder="60s"
            .value=${(this._scalingForm.cooldown as string) ?? '60s'}
            @change=${(e: Event) => { this._scalingForm = { ...this._scalingForm, cooldown: (e.target as HTMLInputElement).value }; }}
          />
        </label>
        <label>Scale-in Cooldown:
          <input type="text" placeholder="120s"
            .value=${(this._scalingForm.scaleInCooldown as string) ?? '120s'}
            @change=${(e: Event) => { this._scalingForm = { ...this._scalingForm, scaleInCooldown: (e.target as HTMLInputElement).value }; }}
          />
        </label>
        <div style="margin-top: 12px;">
          <button class="action-btn" @click=${() => { this._updatePool(this._scalingForm); this._editingScaling = false; this._scalingForm = {}; }}>Save</button>
        </div>
      </div>
    `;
  }

  private _renderEventLog() {
    return html`
      <h3>Event Log</h3>
      <div class="event-log">
        ${this._events.length === 0
          ? html`<div class="empty-state">No events yet — scaling decisions will appear here</div>`
          : this._events.map(evt => html`
            <div style="padding: 4px 0; border-bottom: 1px solid var(--pages-neutral-3); font-size: var(--pages-font-size-xs);">
              <span style="color: var(--pages-neutral-6);">${evt.timestamp ? new Date(evt.timestamp).toLocaleTimeString() : ''}</span>
              <span class="type-badge" style="margin: 0 8px;">${evt.type}</span>
              <span>${this._eventSummary(evt)}</span>
            </div>
          `)
        }
      </div>
    `;
  }

  private _eventSummary(evt: PoolEvent): string {
    switch (evt.type) {
      case 'scaling': return `${evt.direction} ${evt.count} — ${evt.reason}`;
      case 'session': return `${evt.event} ${evt.identity || evt.instanceId}`;
      case 'health': return `${evt.previous} → ${evt.current}`;
      default: return JSON.stringify(evt);
    }
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'claudony-pool-panel': ClaudonyPoolPanel;
  }
}
