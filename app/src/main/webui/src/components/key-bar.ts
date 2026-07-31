import { LitElement, html, css, nothing } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import '@casehubio/pages-ui-components';

type TabChangedHandler = EventListener & ((e: CustomEvent) => void);

const KEYS = [
  { code: '\x1b', label: 'Esc' }, { code: '\x03', label: 'Ctrl+C' },
  { code: '\x04', label: 'Ctrl+D' }, { code: '\x09', label: 'Tab' },
  { code: '`', label: '`' }, { code: '|', label: '|' },
  { code: '~', label: '~' },
  { code: '\x1b[A', label: '↑' }, { code: '\x1b[B', label: '↓' },
  { code: '\x1b[C', label: '→' }, { code: '\x1b[D', label: '←' },
];

@customElement('claudony-key-bar')
export class ClaudonyKeyBar extends LitElement {
  @state() private _isTouch = false;
  @state() private _terminalActive = true;

  private _onTabChanged: TabChangedHandler = ((e: CustomEvent) => {
    if (e.detail?.topic === 'active-tab-changed') {
      this._terminalActive = e.detail.payload.tab === 'terminal';
    }
  }) as TabChangedHandler;

  static override styles = css`
    :host { display: block; }
    :host([hidden]) { display: none; }
    .key-bar {
      display: flex; gap: 4px; padding: 6px 8px;
      background: var(--pages-neutral-2); border-top: 1px solid var(--pages-neutral-4);
      overflow-x: auto; flex-shrink: 0;
    }
    .key-bar::-webkit-scrollbar { display: none; }
    @media (max-width: 1024px) {
      pages-button { min-height: 44px; min-width: 44px; }
      .key-bar { padding: 4px 8px; }
    }
  `;

  connectedCallback(): void {
    super.connectedCallback();
    this._isTouch = 'ontouchstart' in window || navigator.maxTouchPoints > 0;
    if (!this._isTouch) this.setAttribute('hidden', '');
    document.addEventListener('pages-event', this._onTabChanged);
  }

  disconnectedCallback(): void {
    super.disconnectedCallback();
    document.removeEventListener('pages-event', this._onTabChanged);
  }

  private _emitKey(code: string): void {
    this.dispatchEvent(new CustomEvent('pages-event', {
      bubbles: true, composed: true,
      detail: { topic: 'key-pressed', payload: { code } },
    }));
  }

  private _emitCompose(): void {
    this.dispatchEvent(new CustomEvent('pages-event', {
      bubbles: true, composed: true,
      detail: { topic: 'compose-requested', payload: {} },
    }));
  }

  override render() {
    if (!this._isTouch || !this._terminalActive) return nothing;
    return html`
      <div class="key-bar">
        ${KEYS.map(k => html`
          <pages-button size="xs" variant="ghost" label=${k.label}
            @click=${() => this._emitKey(k.code)}></pages-button>
        `)}
        <pages-button size="xs" variant="ghost" label="Compose"
          @click=${() => this._emitCompose()}></pages-button>
      </div>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'claudony-key-bar': ClaudonyKeyBar;
  }
}
