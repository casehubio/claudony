import { LitElement, html, css } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import { attachTerminal, type TerminalHandle } from '../util/terminal-controller.js';
import type { PagesTerminal } from '@casehubio/pages-component-terminal';
import './worker-panel.js';
import type { ClaudonyWorkerPanel } from './worker-panel.js';
import './channel-panel.js';
import type { ClaudonyChannelPanel } from './channel-panel.js';

interface WorkspaceConfig {
  sessionId: string;
  sessionName: string;
  proxyPeer?: string;
  caseId?: string;
  roleName?: string;
  status?: string;
  createdAt?: string;
  channel?: string;
}

@customElement('claudony-terminal-workspace')
export class ClaudonyTerminalWorkspace extends LitElement {
  @state() private _activeTab: 'terminal' | 'chat' = 'terminal';
  private _config: WorkspaceConfig | null = null;
  private _handle: TerminalHandle | null = null;

  static override styles = css`
    :host { display: flex; flex: 1; overflow: hidden; }
    #terminal-container { flex: 1; overflow: hidden; display: flex; }
    #terminal-container pages-component-terminal { flex: 1; overflow: hidden; }
    pages-component-terminal .xterm { height: 100%; }
    pages-component-terminal .xterm-viewport { overflow: hidden !important; }
    .tab-content { display: contents; }
    .tab-panel { display: contents; }
    .tab-bar { display: none; }
    @media (max-width: 767px) {
      :host { flex-direction: column; }
      .tab-content { display: flex; position: relative; flex: 1; overflow: hidden; }
      .tab-panel { display: flex; position: absolute; inset: 0; visibility: hidden; flex-direction: column; }
      .tab-panel.active { visibility: visible; z-index: 1; }
      .tab-bar {
        display: flex; height: 48px;
        border-top: 1px solid var(--pages-neutral-4, #3e3e42);
        background: var(--pages-neutral-2, #252526);
        padding-bottom: env(safe-area-inset-bottom);
        flex-shrink: 0;
      }
      .tab-btn {
        flex: 1; display: flex; align-items: center; justify-content: center;
        background: none; border: none; color: var(--pages-neutral-8, #888);
        font-size: var(--pages-font-size-base); cursor: pointer; min-height: 44px;
      }
      .tab-btn[aria-selected="true"] { color: var(--pages-accent-9, #6366f1); }
    }
    .landscape-nav { display: none; }
    @media (orientation: landscape) and (max-height: 500px) {
      .tab-bar { display: none !important; }
      .landscape-nav {
        display: flex;
        position: fixed;
        top: env(safe-area-inset-top, 0);
        left: env(safe-area-inset-left, 0);
        z-index: 100;
        gap: 4px;
        padding: 4px;
        pointer-events: none;
      }
      .landscape-nav button {
        pointer-events: auto;
        width: 44px;
        height: 44px;
        border-radius: 50%;
        border: none;
        background: rgba(30, 30, 30, 0.7);
        color: var(--pages-neutral-11, #ccc);
        font-size: var(--pages-font-size-xl);
        cursor: pointer;
        display: flex;
        align-items: center;
        justify-content: center;
        backdrop-filter: blur(4px);
        -webkit-backdrop-filter: blur(4px);
      }
      .landscape-nav button:hover {
        background: rgba(30, 30, 30, 0.9);
      }
    }
  `;

  override firstUpdated(): void {
    this.addEventListener('pages-event', ((e: CustomEvent) => {
      const { topic, payload } = e.detail;
      switch (topic) {
        case 'terminal-resize': this._handle?.resize(payload.cols, payload.rows); break;
        case 'key-pressed': this._handle?.sendInput(payload.code); break;
        case 'worker-selected': this._handleWorkerSwitch(payload.sessionId, payload.name); break;
      }
    }) as EventListener);
  }

  configure(config: WorkspaceConfig): void {
    this._config = config;

    const container = this.renderRoot.querySelector('#terminal-container') as HTMLElement;
    if (container && !this._handle) {
      this._handle = attachTerminal(container, config.sessionId, { proxyPeer: config.proxyPeer });
    } else if (this._handle) {
      this._handle.switchSession(config.sessionId, { proxyPeer: config.proxyPeer });
    }

    const workerPanel = this.renderRoot.querySelector('claudony-worker-panel') as ClaudonyWorkerPanel | null;
    workerPanel?.configure({ sessionId: config.sessionId });
    if (config.caseId) workerPanel?.open();

    const channelPanel = this.renderRoot.querySelector('claudony-channel-panel') as ClaudonyChannelPanel | null;
    channelPanel?.configure({
      sessionId: config.sessionId,
      caseId: config.caseId,
      roleName: config.roleName,
      createdAt: config.createdAt,
      channel: config.channel,
      status: config.status,
    });

    if ((window as unknown as Record<string, unknown>).__CLAUDONY_TEST_MODE__ && this._handle?.getTerminal()) {
      (window as unknown as Record<string, unknown>)._xtermTerminal = this._handle.getTerminal()!.terminal;
    }
  }

  destroy(): void {
    this._handle?.dispose();
    (this.renderRoot.querySelector('claudony-worker-panel') as ClaudonyWorkerPanel | null)?.destroy();
    (this.renderRoot.querySelector('claudony-channel-panel') as ClaudonyChannelPanel | null)?.destroy();
  }

  getTerminal(): PagesTerminal | null {
    return this._handle?.getTerminal() ?? null;
  }

  toggleWorkers(): void {
    (this.renderRoot.querySelector('claudony-worker-panel') as ClaudonyWorkerPanel | null)?.toggle();
  }

  toggleChannels(): void {
    (this.renderRoot.querySelector('claudony-channel-panel') as ClaudonyChannelPanel | null)?.toggle();
  }

  private _navigateBack(): void {
    window.location.href = '/app/';
  }

  private _toggleLandscapeTab(): void {
    this._switchTab(this._activeTab === 'terminal' ? 'chat' : 'terminal');
  }

  private _switchTab(tab: 'terminal' | 'chat'): void {
    this._activeTab = tab;
    this.dispatchEvent(new CustomEvent('pages-event', {
      bubbles: true, composed: true,
      detail: { topic: 'active-tab-changed', payload: { tab } },
    }));
    if (tab === 'terminal') {
      requestAnimationFrame(() => {
        const term = this.renderRoot.querySelector('pages-component-terminal');
        if (term && 'fit' in term) (term as { fit(): void }).fit();
      });
    }
  }

  override render() {
    return html`
      <div class="landscape-nav">
        <button @click=${this._navigateBack} aria-label="Back to sessions">←</button>
        <button @click=${this._toggleLandscapeTab} aria-label="Toggle chat">💬</button>
      </div>
      <claudony-worker-panel></claudony-worker-panel>
      <div class="tab-content">
        <div class="tab-panel ${this._activeTab === 'terminal' ? 'active' : ''}">
          <div id="terminal-container"></div>
        </div>
        <div class="tab-panel ${this._activeTab === 'chat' ? 'active' : ''}">
          <claudony-channel-panel></claudony-channel-panel>
        </div>
      </div>
      <nav class="tab-bar" role="tablist" aria-label="Panel navigation">
        <button class="tab-btn" role="tab" aria-selected=${this._activeTab === 'terminal'}
          @click=${() => this._switchTab('terminal')}>Terminal</button>
        <button class="tab-btn" role="tab" aria-selected=${this._activeTab === 'chat'}
          @click=${() => this._switchTab('chat')}>Chat</button>
      </nav>
    `;
  }

  private _handleWorkerSwitch(newSessionId: string, newName: string): void {
    if (!this._config) return;
    this._config.sessionId = newSessionId;
    this._config.sessionName = newName;

    history.replaceState(null, '', '?id=' + newSessionId + '&name=' + encodeURIComponent(newName));
    this._handle?.switchSession(newSessionId, { proxyPeer: this._config.proxyPeer });

    const workerPanel = this.renderRoot.querySelector('claudony-worker-panel') as ClaudonyWorkerPanel | null;
    workerPanel?.configure({ sessionId: newSessionId });

    const channelPanel = this.renderRoot.querySelector('claudony-channel-panel') as ClaudonyChannelPanel | null;
    channelPanel?.configure({
      sessionId: newSessionId,
      caseId: this._config.caseId,
      roleName: this._config.roleName,
      createdAt: this._config.createdAt,
    });

    this.dispatchEvent(new CustomEvent('pages-event', {
      bubbles: true, composed: true,
      detail: { topic: 'session-changed', payload: { sessionName: newName, sessionId: newSessionId } },
    }));

    if ((window as unknown as Record<string, unknown>).__CLAUDONY_TEST_MODE__ && this._handle?.getTerminal()) {
      (window as unknown as Record<string, unknown>)._xtermTerminal = this._handle.getTerminal()!.terminal;
    }
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'claudony-terminal-workspace': ClaudonyTerminalWorkspace;
  }
}
