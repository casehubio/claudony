import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import '@casehubio/pages-ui-components';

@customElement('claudony-terminal-header')
export class ClaudonyTerminalHeader extends LitElement {
  @state() private _sessionName = 'Session';
  @state() private _sessionId = '';
  @state() private _statusText = 'connecting';
  @state() private _statusVariant: 'success' | 'warning' | 'neutral' = 'neutral';
  @property({ type: Boolean, attribute: 'hide-fleet-controls' }) hideFleetControls = false;

  static override styles = css`
    :host { display: block; }
    .terminal-header {
      display: flex; align-items: center; gap: 12px;
      padding: 10px 16px; background: var(--pages-neutral-2);
      border-bottom: 1px solid var(--pages-neutral-4); flex-shrink: 0;
    }
    .back-link {
      color: var(--pages-accent-9); text-decoration: none;
      font-size: var(--pages-font-size-lg); white-space: nowrap;
    }
    .back-link:hover { text-decoration: underline; }
    .session-name {
      font-weight: 600; font-size: var(--pages-font-size-lg); flex: 1;
      overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    }
    @media (max-width: 1024px) {
      .terminal-header { padding: 8px 12px; gap: 8px; }
      .back-link { font-size: 0; }
      .back-link::before { content: '\2190'; font-size: var(--pages-font-size-xl); }
      .session-name { font-size: var(--pages-font-size-base); }
      pages-button { min-height: 44px; }
    }
    @media (max-width: 767px) {
      .terminal-header { padding: 6px 10px; gap: 6px; }
      .session-name { font-size: var(--pages-font-size-base); max-width: 40vw; }
    }
    @media (orientation: landscape) and (max-height: 500px) {
      :host { display: none !important; }
    }
  `;

  configure(opts: { sessionName?: string; sessionId?: string }): void {
    if (opts.sessionName !== undefined) this._sessionName = opts.sessionName;
    if (opts.sessionId !== undefined) this._sessionId = opts.sessionId;
    document.title = this._sessionName;
  }

  updateStatus(text: string, cssClass: string): void {
    this._statusText = text;
    switch (cssClass) {
      case 'active': this._statusVariant = 'success'; break;
      case 'waiting': this._statusVariant = 'warning'; break;
      default: this._statusVariant = 'neutral';
    }
  }

  private _emit(topic: string): void {
    this.dispatchEvent(new CustomEvent('pages-event', {
      bubbles: true, composed: true,
      detail: { topic, payload: {} },
    }));
  }

  override render() {
    return html`
      <header class="terminal-header">
        <a href="/app/" class="back-link">&larr; Sessions</a>
        <span class="session-name" id="session-name">${this._sessionName}</span>
        <pages-badge id="status-badge" label=${this._statusText}
          variant=${this._statusVariant} size="sm"></pages-badge>
        <pages-button variant="ghost" size="sm" label="Compose"
          title="Compose text (Ctrl+G)"
          @click=${() => this._emit('compose-requested')}></pages-button>
        ${!this.hideFleetControls ? html`
          <pages-button variant="ghost" size="sm" label="Workers"
            title="Toggle workers panel"
            @click=${() => this._emit('workers-toggle')}></pages-button>
          <pages-button variant="ghost" size="sm" label="Channels"
            title="Toggle channel panel (Ctrl+K)"
            @click=${() => this._emit('channels-toggle')}></pages-button>
        ` : nothing}
      </header>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'claudony-terminal-header': ClaudonyTerminalHeader;
  }
}
