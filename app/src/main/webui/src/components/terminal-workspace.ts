import "@casehubio/pages-component-terminal";
import type { PagesTerminal } from "@casehubio/pages-component-terminal";
import "./worker-panel";
import type { ClaudonyWorkerPanel } from "./worker-panel";
import "./channel-panel";
import type { ClaudonyChannelPanel } from "./channel-panel";
import { fetchWithAuth } from "../util/auth";

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
  private _terminal: PagesTerminal | null = null;
  private _workerPanel: ClaudonyWorkerPanel | null = null;
  private _channelPanel: ClaudonyChannelPanel | null = null;

  connectedCallback(): void {
    this.render();
    this.wireEvents();
  }

  configure(config: WorkspaceConfig): void {
    this._config = config;

    const proto = location.protocol === "https:" ? "wss:" : "ws:";
    const wsUrl = config.proxyPeer
      ? `${proto}//${location.host}/ws/proxy/${config.proxyPeer}/${config.sessionId}/{cols}/{rows}`
      : `${proto}//${location.host}/ws/${config.sessionId}/{cols}/{rows}`;

    this._terminal?.configure({ wsUrl });

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

    if ((window as unknown as Record<string, unknown>).__CLAUDONY_TEST_MODE__ && this._terminal) {
      (window as unknown as Record<string, unknown>)._xtermTerminal = this._terminal.terminal;
    }
  }

  destroy(): void {
    this._workerPanel?.destroy();
    this._channelPanel?.destroy();
  }

  getTerminal(): PagesTerminal | null {
    return this._terminal;
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
        pages-component-terminal {
          flex: 1; overflow: hidden;
        }
        pages-component-terminal .xterm { height: 100%; }
        pages-component-terminal .xterm-viewport { overflow: hidden !important; }
      </style>
      <claudony-worker-panel id="case-panel"></claudony-worker-panel>
      <pages-component-terminal></pages-component-terminal>
      <claudony-channel-panel id="channel-panel"></claudony-channel-panel>
    `;

    this._terminal = this.querySelector("pages-component-terminal") as PagesTerminal;
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
          this._terminal?.sendInput(payload.code);
          break;
        case "worker-selected":
          this.handleWorkerSwitch(payload.sessionId, payload.name);
          break;
      }
    }) as EventListener);
  }

  private handleResize(cols: number, rows: number): void {
    if (!this._config) return;
    const resizeUrl = this._config.proxyPeer
      ? `/api/peers/${this._config.proxyPeer}/sessions/${this._config.sessionId}/resize?cols=${cols}&rows=${rows}`
      : `/api/sessions/${this._config.sessionId}/resize?cols=${cols}&rows=${rows}`;
    fetchWithAuth(resizeUrl, { method: "POST" }).catch(() => {});
  }

  private handleWorkerSwitch(newSessionId: string, newName: string): void {
    if (!this._config) return;

    this._config.sessionId = newSessionId;
    this._config.sessionName = newName;

    history.replaceState(null, "",
      "?id=" + newSessionId + "&name=" + encodeURIComponent(newName));

    const proto = location.protocol === "https:" ? "wss:" : "ws:";
    const wsUrl = this._config.proxyPeer
      ? `${proto}//${location.host}/ws/proxy/${this._config.proxyPeer}/${newSessionId}/{cols}/{rows}`
      : `${proto}//${location.host}/ws/${newSessionId}/{cols}/{rows}`;

    this._terminal?.configure({ wsUrl });

    this._workerPanel?.configure({ sessionId: newSessionId });
    this._channelPanel?.configure({
      sessionId: newSessionId,
      caseId: this._config.caseId,
      roleName: this._config.roleName,
      createdAt: this._config.createdAt,
    });

    // Dispatch for header to pick up
    this.dispatchEvent(new CustomEvent("pages-event", {
      bubbles: true, composed: true,
      detail: { topic: "session-changed", payload: { sessionName: newName, sessionId: newSessionId } },
    }));

    if ((window as unknown as Record<string, unknown>).__CLAUDONY_TEST_MODE__ && this._terminal) {
      (window as unknown as Record<string, unknown>)._xtermTerminal = this._terminal.terminal;
    }
  }
}

customElements.define("claudony-terminal-workspace", ClaudonyTerminalWorkspace);
