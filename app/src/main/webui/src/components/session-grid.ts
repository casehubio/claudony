import { LitElement, html, css, nothing } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import '@casehubio/pages-ui-components';
import '@casehubio/pages-primitives/modal';
import { fetchWithAuth } from '../util/auth.js';
import { THEME_CSS } from '../theme.js';

interface Session {
  id: string;
  name: string;
  status: string;
  workingDir: string;
  lastActive: string;
  caseId?: string;
  roleName?: string;
  instanceUrl?: string;
  instanceName?: string;
  stale?: boolean;
}

interface PrData {
  gitRepo?: boolean;
  githubRepo?: string;
  branch?: string;
  error?: string;
  pr?: { number: number; title: string; state: string; url: string; checksTotal: number; checksPassed: number; checksPending: number; checksFailed: number };
}

interface ServicePort { port: number; responseMs: number }

const POLL_INTERVAL = 5000;

@customElement('claudony-session-grid')
export class ClaudonySessionGrid extends LitElement {
  @state() private _sessions: Session[] = [];
  @state() private _showNewDialog = false;
  @state() private _showAuthDialog = false;
  @state() private _nameError = '';
  @state() private _gitStatus: Record<string, string> = {};
  @state() private _serviceStatus: Record<string, string> = {};

  private _pollTimer: ReturnType<typeof setInterval> | null = null;
  private _newName = '';
  private _newWorkingDir = '';

  static override styles = css`
    ${THEME_CSS}
    :host { display: block; height: 100%; overflow-y: auto; padding: 1rem; }
    .header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 1rem; }
    .header h2 { margin: 0; font-size: 1.1rem; }
    .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 0.75rem; }
    .card {
      background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius);
      padding: 0.75rem; cursor: pointer; transition: border-color 0.15s;
    }
    .card:hover { border-color: var(--accent); }
    .card-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 0.4rem; gap: 6px; }
    .card-name { font-weight: 600; font-size: 0.95rem; flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .card-dir { font-size: 0.8rem; color: var(--text-muted); margin-bottom: 0.3rem; font-family: monospace; }
    .card-time { font-size: 0.75rem; color: var(--text-muted); }
    .card-actions { display: flex; gap: 0.4rem; margin-top: 0.5rem; flex-wrap: wrap; }
    .card-git, .card-services { font-size: 12px; margin-bottom: 6px; min-height: 16px; display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
    .stale { opacity: 0.6; }
    .empty { text-align: center; color: var(--text-muted); padding: 3rem 1rem; }
    .form-field { margin-bottom: 16px; }
    .auth-body { text-align: center; }
    .auth-body p { color: var(--pages-neutral-8); margin: 0 0 1.5rem; font-size: 0.9rem; }
    .auth-actions { display: flex; flex-direction: column; gap: 0.75rem; }
    .pr-link { color: var(--pages-accent-9); text-decoration: none; }
    .pr-link:hover { text-decoration: underline; }
    @media (max-width: 1024px) {
      .grid { grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 0.6rem; }
      :host { padding: 0.75rem; }
    }
    @media (max-width: 767px) {
      .grid { grid-template-columns: 1fr; gap: 0.6rem; }
      :host { padding: 0.6rem; }
    }
  `;

  connectedCallback(): void {
    super.connectedCallback();
    this._fetchSessions();
    this._pollTimer = setInterval(() => this._fetchSessions(), POLL_INTERVAL);
  }

  disconnectedCallback(): void {
    super.disconnectedCallback();
    if (this._pollTimer) { clearInterval(this._pollTimer); this._pollTimer = null; }
  }

  private async _fetchSessions(): Promise<void> {
    try {
      const res = await fetchWithAuth('/api/sessions');
      if (res.status === 401) { this._showAuthDialog = true; return; }
      if (!res.ok) return;
      this._sessions = await res.json();
    } catch { /* ignore */ }
  }

  private _displayName(name: string): string { return name.replace(/^claudony-/, ''); }

  private _timeAgo(iso: string): string {
    const diff = Date.now() - new Date(iso).getTime();
    const m = Math.floor(diff / 60000);
    if (m < 1) return 'just now';
    if (m < 60) return m + 'm ago';
    const h = Math.floor(m / 60);
    if (h < 24) return h + 'h ago';
    return Math.floor(h / 24) + 'd ago';
  }

  private _statusVariant(status: string): 'success' | 'warning' | 'neutral' {
    const s = status.toLowerCase();
    return s === 'active' ? 'success' : s === 'waiting' ? 'warning' : 'neutral';
  }

  private _isLocalhost(): boolean {
    return window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1';
  }

  private _openUrl(s: Session): string {
    const name = this._displayName(s.name);
    if (!s.instanceUrl) return '/app/session.html?id=' + s.id + '&name=' + encodeURIComponent(name);
    const fleetPanel = document.querySelector('claudony-fleet-panel') as any;
    const peerInfo = fleetPanel?.peerTerminalModes?.[s.instanceUrl];
    if (peerInfo?.terminalMode === 'PROXY') {
      return '/app/session.html?id=' + s.id + '&name=' + encodeURIComponent(name) + '&proxyPeer=' + encodeURIComponent(peerInfo.id);
    }
    return s.instanceUrl + '/app/session.html?id=' + s.id + '&name=' + encodeURIComponent(name);
  }

  private async _deleteSession(id: string): Promise<void> {
    if (!confirm('Delete this session?')) return;
    await fetchWithAuth('/api/sessions/' + id, { method: 'DELETE' });
    this._fetchSessions();
  }

  private async _openInITerm(id: string): Promise<void> {
    const r = await fetchWithAuth('/api/sessions/' + id + '/open-terminal', { method: 'POST' });
    if (r.status === 503) alert('No terminal adapter available on this machine.');
  }

  private async _checkGitStatus(id: string): Promise<void> {
    this._gitStatus = { ...this._gitStatus, [id]: 'loading...' };
    try {
      const r = await fetchWithAuth('/api/sessions/' + id + '/git-status');
      if (!r.ok) { this._gitStatus = { ...this._gitStatus, [id]: 'fetch failed' }; return; }
      const data: PrData = await r.json();
      let display = '';
      if (!data.gitRepo) display = 'not a git repo';
      else if (!data.githubRepo) display = (data.branch || '') + ' — no GitHub remote';
      else if (data.error) display = data.branch + ' — ⚠ ' + data.error;
      else if (!data.pr) display = data.branch + ' — no open PR';
      else {
        const pr = data.pr;
        display = data.branch + ' — #' + pr.number + ' ' + pr.title;
        if (pr.checksTotal > 0) display += ' [' + pr.checksPassed + '✓/' + pr.checksPending + '⟳/' + pr.checksFailed + '✗]';
      }
      this._gitStatus = { ...this._gitStatus, [id]: display };
    } catch { this._gitStatus = { ...this._gitStatus, [id]: 'fetch failed' }; }
  }

  private async _checkServices(id: string): Promise<void> {
    this._serviceStatus = { ...this._serviceStatus, [id]: 'checking...' };
    try {
      const r = await fetchWithAuth('/api/sessions/' + id + '/service-health');
      if (!r.ok) { this._serviceStatus = { ...this._serviceStatus, [id]: 'check failed' }; return; }
      const ports: ServicePort[] = await r.json();
      this._serviceStatus = { ...this._serviceStatus, [id]: ports.length === 0 ? 'none detected' : ports.map(p => ':' + p.port).join(' ') };
    } catch { this._serviceStatus = { ...this._serviceStatus, [id]: 'check failed' }; }
  }

  private _validateName(): void {
    const exists = this._sessions.some(s => this._displayName(s.name) === this._newName.trim());
    this._nameError = exists ? 'A session named "' + this._newName.trim() + '" already exists' : '';
  }

  private async _createSession(overwrite = false): Promise<void> {
    const name = this._newName.trim();
    if (!name) return;
    const body: Record<string, string> = { name };
    if (this._newWorkingDir) body.workingDir = this._newWorkingDir;
    const r = await fetchWithAuth('/api/sessions' + (overwrite ? '?overwrite=true' : ''), {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
    });
    if (r.status === 401) { this._showAuthDialog = true; return; }
    if (r.ok) { this._showNewDialog = false; this._newName = ''; this._newWorkingDir = ''; this._nameError = ''; this._fetchSessions(); }
  }

  private async _devLogin(): Promise<void> {
    const r = await fetch('/auth/dev-login', { method: 'POST' });
    if (r.ok) { this._showAuthDialog = false; this._fetchSessions(); }
    else if (r.status === 404) { window.location.href = '/auth/login'; }
  }

  override render() {
    return html`
      <div class="header">
        <h2>Sessions</h2>
        <pages-button variant="primary" size="sm" label="+ New Session"
          @click=${() => { this._showNewDialog = true; }}></pages-button>
      </div>
      <div class="grid">
        ${this._sessions.length === 0
          ? html`<div class="empty"><p>No active sessions</p>
              <pages-button variant="primary" label="+ Create your first session"
                @click=${() => { this._showNewDialog = true; }}></pages-button></div>`
          : this._sessions.map(s => this._renderCard(s))}
      </div>
      ${this._renderNewSessionDialog()}
      ${this._renderAuthDialog()}
    `;
  }

  private _renderCard(s: Session) {
    const name = this._displayName(s.name);
    const hasWorkingDir = s.workingDir && s.workingDir !== 'unknown';
    const isLocal = !s.instanceUrl;
    const openUrl = this._openUrl(s);
    return html`
      <div class="card ${s.stale ? 'stale' : ''}" @click=${() => { window.location.href = openUrl; }}>
        <div class="card-header">
          <span class="card-name">${name}</span>
          <pages-badge label=${s.status.toLowerCase()} variant=${this._statusVariant(s.status)} size="sm"></pages-badge>
          ${s.instanceUrl ? html`<pages-badge label=${s.instanceName || s.instanceUrl} variant="accent" size="sm"></pages-badge>` : nothing}
        </div>
        ${s.stale ? html`<pages-badge label=${'⏰ ' + this._timeAgo(s.lastActive)} variant="warning" size="sm"></pages-badge>` : nothing}
        <div class="card-dir">${s.workingDir}</div>
        <div class="card-time">Active ${this._timeAgo(s.lastActive)}</div>
        ${hasWorkingDir && isLocal ? html`<div class="card-git">${this._gitStatus[s.id] || ''}</div>` : nothing}
        ${isLocal ? html`<div class="card-services">${this._serviceStatus[s.id] || ''}</div>` : nothing}
        <div class="card-actions" @click=${(e: Event) => e.stopPropagation()}>
          <pages-button size="xs" variant="ghost" label="Open" @click=${() => { window.location.href = openUrl; }}></pages-button>
          ${hasWorkingDir && isLocal ? html`<pages-button size="xs" variant="ghost" label="Check PR" @click=${() => this._checkGitStatus(s.id)}></pages-button>` : nothing}
          ${isLocal ? html`<pages-button size="xs" variant="ghost" label="Check Services" @click=${() => this._checkServices(s.id)}></pages-button>` : nothing}
          ${this._isLocalhost() && isLocal ? html`<pages-button size="xs" variant="ghost" label="Open in iTerm2" @click=${() => this._openInITerm(s.id)}></pages-button>` : nothing}
          ${isLocal ? html`<pages-button size="xs" variant="danger" label="Delete" @click=${() => this._deleteSession(s.id)}></pages-button>` : nothing}
        </div>
      </div>
    `;
  }

  private _renderNewSessionDialog() {
    return html`
      <pages-modal .open=${this._showNewDialog} @pages-modal-close=${() => { this._showNewDialog = false; this._nameError = ''; }}>
        <span slot="header">New Session</span>
        <div class="form-field">
          <pages-input label="Name" placeholder="myproject" required
            @input=${(e: Event) => { this._newName = (e.target as any).value; this._validateName(); }}
            .error=${this._nameError || undefined}></pages-input>
        </div>
        <div class="form-field">
          <pages-input label="Working Directory" placeholder="~/claudony-workspace/{name} (default)"
            @input=${(e: Event) => { this._newWorkingDir = (e.target as any).value; }}></pages-input>
        </div>
        <div slot="actions">
          <pages-button variant="ghost" label="Cancel" @click=${() => { this._showNewDialog = false; this._nameError = ''; }}></pages-button>
          ${this._nameError ? html`<pages-button variant="danger" label="Overwrite" @click=${() => this._createSession(true)}></pages-button>` : nothing}
          <pages-button variant="primary" label="Create" @click=${() => this._createSession(false)}></pages-button>
        </div>
      </pages-modal>
    `;
  }

  private _renderAuthDialog() {
    return html`
      <pages-modal .open=${this._showAuthDialog} variant="alertdialog" @pages-modal-close=${() => { this._showAuthDialog = false; }}>
        <span slot="header">Not authenticated</span>
        <div class="auth-body">
          <p>Dev mode: click below to log in instantly.<br>Production: use passkey login.</p>
          <div class="auth-actions">
            <pages-button variant="primary" label="Quick Dev Login" @click=${() => this._devLogin()}></pages-button>
            <pages-button variant="ghost" label="Sign in with Passkey" @click=${() => { window.location.href = '/auth/login'; }}></pages-button>
          </div>
        </div>
      </pages-modal>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'claudony-session-grid': ClaudonySessionGrid;
  }
}
