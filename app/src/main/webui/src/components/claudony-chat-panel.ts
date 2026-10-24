import { LitElement, html, css } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import { ConversationWorkbenchElement } from '@casehubio/mosaic-channel-activity';
import type { ConversationWorkbenchConfig, ConnectPushOptions, PushConnection, SendMessagePayload } from '@casehubio/mosaic-channel-activity';

void ConversationWorkbenchElement;
import { createEventConnection } from '@casehubio/pages-data/dataset/external/sources/event-connection.js';
import type { EventConnection } from '@casehubio/pages-data/dataset/external/sources/event-connection.js';
import { fetchWithAuth } from '../util/auth.js';

@customElement('claudony-chat-panel')
export class ClaudonyChatPanel extends LitElement {
  @property({ type: String }) namespace = '';

  static override styles = css`
    :host { display: flex; flex: 1; overflow: hidden; }
    agency-conversation-workbench { flex: 1; }
  `;

  private _buildConfig(): ConversationWorkbenchConfig {
    const ns = this.namespace;
    return {
      fetch: fetchWithAuth,
      currentActorId: 'human',
      restBase: '/api',
      connectPush: (options: ConnectPushOptions): PushConnection => {
        const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
        const url = `${proto}//${location.host}/ws/push`;
        const eventTarget = new EventTarget();
        const conn = createEventConnection(url, {
          config: { eventTarget },
          onStatusChange: (status: string) => { options.onStatusChange(status as any); },
        });
        conn.listen(options.topics);
        eventTarget.addEventListener('pages-event', (e: Event) => {
          const detail = (e as CustomEvent).detail;
          if (detail?.payload) options.onOp(detail.payload);
        });
        return { close: () => conn.close() };
      },
      channelFilter: ns ? (ch) => ch.name.startsWith(ns) : undefined,
      sendMessage: async (payload: SendMessagePayload) => {
        const body: Record<string, unknown> = {
          content: payload.content,
          type: payload.speechAct || 'command',
        };
        if (payload.inReplyTo) body.inReplyTo = Number(payload.inReplyTo);
        if (payload.topic) body.topic = payload.topic;
        await fetchWithAuth(
          `/api/mesh/channels/${encodeURIComponent(payload.channelId)}/messages`,
          {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body),
          },
        );
      },
    };
  }

  override render() {
    return html`
      <agency-conversation-workbench .config=${this._buildConfig()}>
      </agency-conversation-workbench>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'claudony-chat-panel': ClaudonyChatPanel;
  }
}
