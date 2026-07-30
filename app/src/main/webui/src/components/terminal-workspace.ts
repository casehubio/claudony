import { LitElement, html, css } from 'lit';
import { customElement } from 'lit/decorators.js';
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
  private _config: WorkspaceConfig | null = null;
  private _handle: TerminalHandle | null = null;

  static override styles = css`
    :host { display: flex; flex: 1; overflow: hidden; }
    #terminal-container { flex: 1; overflow: hidden; display: flex; }
    #terminal-container pages-component-terminal { flex: 1; overflow: hidden; }
    pages-component-terminal .xterm { height: 100%; }
    pages-component-terminal .xterm-viewport { overflow: hidden !important; }
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

  override render() {
    return html`
      <claudony-worker-panel></claudony-worker-panel>
      <div id="terminal-container"></div>
      <claudony-channel-panel></claudony-channel-panel>
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
