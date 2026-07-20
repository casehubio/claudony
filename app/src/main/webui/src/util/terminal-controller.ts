import "@casehubio/pages-component-terminal";
import type { PagesTerminal } from "@casehubio/pages-component-terminal";
import { fetchWithAuth } from "./auth";

export interface TerminalHandle {
  dispose(): void;
  resize(cols: number, rows: number): void;
  sendInput(text: string): void;
  switchSession(sessionId: string, opts?: { proxyPeer?: string }): void;
  getTerminal(): PagesTerminal | null;
  paste(text: string): void;
}

function buildWsUrl(sessionId: string, proxyPeer?: string): string {
  const proto = location.protocol === "https:" ? "wss:" : "ws:";
  return proxyPeer
    ? `${proto}//${location.host}/ws/proxy/${proxyPeer}/${sessionId}/{cols}/{rows}`
    : `${proto}//${location.host}/ws/${sessionId}/{cols}/{rows}`;
}

export function attachTerminal(
  container: HTMLElement,
  sessionId: string,
  opts?: { proxyPeer?: string },
): TerminalHandle {
  const el = document.createElement("pages-component-terminal") as PagesTerminal;
  container.appendChild(el);

  let currentSessionId = sessionId;
  let currentProxyPeer = opts?.proxyPeer;

  el.configure({ wsUrl: buildWsUrl(sessionId, opts?.proxyPeer) });

  return {
    dispose() {
      el.remove();
    },

    resize(cols: number, rows: number) {
      const resizeUrl = currentProxyPeer
        ? `/api/peers/${currentProxyPeer}/sessions/${currentSessionId}/resize?cols=${cols}&rows=${rows}`
        : `/api/sessions/${currentSessionId}/resize?cols=${cols}&rows=${rows}`;
      fetchWithAuth(resizeUrl, { method: "POST" }).catch(() => {});
    },

    sendInput(text: string) {
      el.sendInput(text);
    },

    switchSession(newSessionId: string, switchOpts?: { proxyPeer?: string }) {
      currentSessionId = newSessionId;
      currentProxyPeer = switchOpts?.proxyPeer ?? currentProxyPeer;
      el.configure({ wsUrl: buildWsUrl(newSessionId, currentProxyPeer) });
    },

    getTerminal() {
      return el;
    },

    paste(text: string) {
      el.paste(text);
    },
  };
}
