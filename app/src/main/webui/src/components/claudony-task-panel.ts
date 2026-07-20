import { LitElement, html, css, nothing } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import type { QhorusMessage } from '@casehubio/blocks-ui-channel-activity';
import { commitmentStateCategory, isObligationCreating } from '@casehubio/blocks-ui-channel-activity';
import { emitPagesEvent } from '@casehubio/blocks-ui-core';
import { ChannelEventTopics } from '@casehubio/blocks-ui-channel-activity';
import type { CommitmentRecord } from '../util/channel-adapter.js';

@customElement('claudony-task-panel')
export class ClaudonyTaskPanelElement extends LitElement {
  @property({ type: Array }) messages: QhorusMessage[] = [];
  @property({ type: Object }) commitments: Map<string, CommitmentRecord> = new Map();
  @property({ type: String }) selectedMessageId?: string;

  static override readonly styles = css`
    :host {
      display: flex;
      flex-direction: column;
      height: 100%;
      overflow-y: auto;
      font-family: var(--pages-font-family, 'Inter', system-ui, sans-serif);
    }
    .panel-title {
      font-size: var(--pages-font-size-sm, 13px);
      font-weight: var(--pages-font-weight-semibold, 600);
      padding: var(--pages-space-3, 12px) var(--pages-space-4, 16px);
      border-bottom: 1px solid var(--pages-neutral-4, #e5e5e5);
      color: var(--pages-neutral-12, #111);
    }
    .group-label {
      font-size: var(--pages-font-size-xs, 11px);
      font-weight: var(--pages-font-weight-medium, 500);
      color: var(--pages-neutral-8, #888);
      text-transform: uppercase;
      letter-spacing: 0.5px;
      padding: var(--pages-space-2, 8px) var(--pages-space-4, 16px) var(--pages-space-1, 4px);
    }
    .task-row {
      display: flex;
      flex-direction: column;
      gap: var(--pages-space-1, 4px);
      padding: var(--pages-space-2, 8px) var(--pages-space-4, 16px);
      cursor: pointer;
      border-bottom: 1px solid var(--pages-neutral-3, #f0f0f0);
    }
    .task-row:hover { background: var(--pages-neutral-2, #f5f5f5); }
    .task-row.selected { background: var(--pages-accent-2, #eef2ff); }
    .task-row.overdue { border-left: 3px solid var(--pages-danger-9, #dc2626); }
    .task-header {
      display: flex;
      align-items: center;
      gap: var(--pages-space-2, 8px);
    }
    .state-badge {
      font-size: 10px;
      font-weight: var(--pages-font-weight-medium, 500);
      padding: 1px 6px;
      border-radius: var(--pages-radius-sm, 4px);
      text-transform: uppercase;
    }
    .badge-active { background: var(--pages-accent-3, #e0e7ff); color: var(--pages-accent-11, #3730a3); }
    .badge-info { background: var(--pages-info-3, #dbeafe); color: var(--pages-info-11, #1e40af); }
    .badge-success { background: var(--pages-success-3, #d1fae5); color: var(--pages-success-11, #065f46); }
    .badge-danger { background: var(--pages-danger-3, #fee2e2); color: var(--pages-danger-11, #991b1b); }
    .badge-neutral { background: var(--pages-neutral-3, #e5e5e5); color: var(--pages-neutral-9, #737373); }
    .badge-transfer { background: var(--pages-info-3, #dbeafe); color: var(--pages-info-11, #1e40af); }
    .badge-warning { background: var(--pages-warning-3, #fef3c7); color: var(--pages-warning-11, #92400e); }
    .sender-target {
      font-size: var(--pages-font-size-xs, 11px);
      color: var(--pages-neutral-9, #737373);
    }
    .content-preview {
      font-size: var(--pages-font-size-sm, 13px);
      color: var(--pages-neutral-11, #333);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .timestamp {
      font-size: var(--pages-font-size-xs, 11px);
      color: var(--pages-neutral-8, #888);
    }
    .deadline-indicator {
      font-size: var(--pages-font-size-xs, 11px);
      color: var(--pages-danger-9, #dc2626);
      font-weight: var(--pages-font-weight-medium, 500);
    }
    .terminal-group { opacity: 0.7; }
    .empty {
      display: flex;
      align-items: center;
      justify-content: center;
      height: 100%;
      color: var(--pages-neutral-8, #888);
      font-size: var(--pages-font-size-sm, 13px);
    }
  `;

  private _commands(): QhorusMessage[] {
    return this.messages.filter(m => isObligationCreating(m.messageType));
  }

  private _isOverdue(record: CommitmentRecord | undefined): boolean {
    if (!record?.deadline) return false;
    return record.state === 'OPEN' && new Date(record.deadline) < new Date();
  }

  private _isTerminal(state: string): boolean {
    return ['FULFILLED', 'FAILED', 'DECLINED', 'DELEGATED', 'EXPIRED'].includes(state);
  }

  private _formatTime(iso: string): string {
    const date = new Date(iso);
    const now = new Date();
    const diffMs = now.getTime() - date.getTime();
    const diffMin = Math.floor(diffMs / 60000);
    if (diffMin < 1) return 'now';
    if (diffMin < 60) return `${diffMin}m`;
    const diffHr = Math.floor(diffMin / 60);
    if (diffHr < 24) return `${diffHr}h`;
    return `${Math.floor(diffHr / 24)}d`;
  }

  private _onRowClick(msg: QhorusMessage) {
    emitPagesEvent(this, ChannelEventTopics.MESSAGE_SELECTED, { message: msg });
  }

  override render() {
    const commands = this._commands();
    if (commands.length === 0) {
      return html`<div class="panel-title">Tasks</div><div class="empty">No commitments in this channel</div>`;
    }

    const active: QhorusMessage[] = [];
    const overdue: QhorusMessage[] = [];
    const terminal: QhorusMessage[] = [];

    for (const cmd of commands) {
      const record = this.commitments.get(cmd.id);
      const state = record?.state ?? 'OPEN';
      if (this._isOverdue(record)) {
        overdue.push(cmd);
      } else if (this._isTerminal(state)) {
        terminal.push(cmd);
      } else {
        active.push(cmd);
      }
    }

    return html`
      <div class="panel-title">Tasks</div>
      ${overdue.length > 0 ? html`
        <div class="group-label">Overdue</div>
        ${overdue.map(m => this._renderRow(m))}
      ` : nothing}
      ${active.length > 0 ? html`
        <div class="group-label">Active</div>
        ${active.map(m => this._renderRow(m))}
      ` : nothing}
      ${terminal.length > 0 ? html`
        <div class="group-label terminal-group">Completed</div>
        <div class="terminal-group">
          ${terminal.map(m => this._renderRow(m))}
        </div>
      ` : nothing}
    `;
  }

  private _renderRow(msg: QhorusMessage) {
    const record = this.commitments.get(msg.id);
    const state = record?.state ?? 'OPEN';
    const category = commitmentStateCategory(state as any);
    const isOverdue = this._isOverdue(record);
    const isSelected = this.selectedMessageId === msg.id;

    return html`
      <div class="task-row ${isOverdue ? 'overdue' : ''} ${isSelected ? 'selected' : ''}"
           @click=${() => this._onRowClick(msg)}>
        <div class="task-header">
          <span class="state-badge badge-${category}">${state}</span>
          <span class="timestamp">${this._formatTime(msg.createdAt)}</span>
          ${isOverdue && record?.deadline ? html`
            <span class="deadline-indicator">overdue</span>
          ` : nothing}
        </div>
        <div class="content-preview">${msg.content.split('\n')[0]}</div>
        <div class="sender-target">
          ${msg.sender}${msg.target ? html` → ${msg.target}` : nothing}
        </div>
      </div>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'claudony-task-panel': ClaudonyTaskPanelElement;
  }
}
