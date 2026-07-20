import { attachTerminal, type TerminalHandle } from "../util/terminal-controller";
import type { PagesTerminal } from "@casehubio/pages-component-terminal";
import "./worker-panel";
import type { ClaudonyWorkerPanel } from "./worker-panel";
import "./channel-panel";
import type { ClaudonyChannelPanel } from "./channel-panel";

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

export class ClaudonyTerminalWorkspace extends HTMLElement {
  private _config: WorkspaceConfig | null = null;
  private _handle: TerminalHandle | null = null;
  private _workerPanel: ClaudonyWorkerPanel | null = null;
  private _channelPanel: ClaudonyChannelPanel | null = null;

  connectedCallback(): void {
    this.render();
    this.wireEvents();
  }

  configure(config: WorkspaceConfig): void {
    this._config = config;

    const container = this.querySelector("#terminal-container") as HTMLElement;
    if (container && !this._handle) {
      this._handle = attachTerminal(container, config.sessionId, { proxyPeer: config.proxyPeer });
    } else if (this._handle) {
      this._handle.switchSession(config.sessionId, { proxyPeer: config.proxyPeer });
    }

    this._workerPanel?.configure({ sessionId: config.sessionId });
    if (config.caseId) {
      this._workerPanel?.open();
    }

    this._channelPanel?.configure({
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
    this._workerPanel?.destroy();
    this._channelPanel?.destroy();
  }

  getTerminal(): PagesTerminal | null {
    return this._handle?.getTerminal() ?? null;
  }

  toggleWorkers(): void {
    this._workerPanel?.toggle();
  }

  toggleChannels(): void {
    this._channelPanel?.toggle();
  }

  private render(): void {
    this.innerHTML = `
      <style>
        claudony-terminal-workspace {
          display: flex; flex: 1; overflow: hidden;
        }
        #terminal-container {
          flex: 1; overflow: hidden; display: flex;
        }
        #terminal-container pages-component-terminal {
          flex: 1; overflow: hidden;
        }
        pages-component-terminal .xterm { height: 100%; }
        pages-component-terminal .xterm-viewport { overflow: hidden !important; }
      </style>
      <claudony-worker-panel id="case-panel"></claudony-worker-panel>
      <div id="terminal-container"></div>
      <claudony-channel-panel id="channel-panel"></claudony-channel-panel>
    `;

    this._workerPanel = this.querySelector("claudony-worker-panel") as ClaudonyWorkerPanel;
    this._channelPanel = this.querySelector("claudony-channel-panel") as ClaudonyChannelPanel;
  }

  private wireEvents(): void {
    this.addEventListener("pages-event", ((e: CustomEvent) => {
      const { topic, payload } = e.detail;

      switch (topic) {
        case "terminal-resize":
          this.handleResize(payload.cols, payload.rows);
          break;
        case "key-pressed":
          this._handle?.sendInput(payload.code);
          break;
        case "worker-selected":
          this.handleWorkerSwitch(payload.sessionId, payload.name);
          break;
      }
    }) as EventListener);
  }

  private handleResize(cols: number, rows: number): void {
    this._handle?.resize(cols, rows);
  }

  private handleWorkerSwitch(newSessionId: string, newName: string): void {
    if (!this._config) return;

    this._config.sessionId = newSessionId;
    this._config.sessionName = newName;

    history.replaceState(null, "",
      "?id=" + newSessionId + "&name=" + encodeURIComponent(newName));

    this._handle?.switchSession(newSessionId, { proxyPeer: this._config.proxyPeer });

    this._workerPanel?.configure({ sessionId: newSessionId });
    this._channelPanel?.configure({
      sessionId: newSessionId,
      caseId: this._config.caseId,
      roleName: this._config.roleName,
      createdAt: this._config.createdAt,
    });

    this.dispatchEvent(new CustomEvent("pages-event", {
      bubbles: true, composed: true,
      detail: { topic: "session-changed", payload: { sessionName: newName, sessionId: newSessionId } },
    }));

    if ((window as unknown as Record<string, unknown>).__CLAUDONY_TEST_MODE__ && this._handle?.getTerminal()) {
      (window as unknown as Record<string, unknown>)._xtermTerminal = this._handle.getTerminal()!.terminal;
    }
  }
}

customElements.define("claudony-terminal-workspace", ClaudonyTerminalWorkspace);
