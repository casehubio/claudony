import type { QhorusMessage, QhorusChannel, MessageType } from '@casehubio/blocks-ui-channel-activity';

export interface TimelineEntry {
  id?: number;
  type?: string;
  message_type?: string;
  sender?: string;
  content?: string | null;
  created_at?: string;
  agent_id?: string;
  tool_name?: string;
  duration_ms?: number | null;
  token_count?: number | null;
}

export interface ChannelInfo {
  name: string;
  message_count?: number;
  allowedTypes?: string | null;
}

function resolveActorType(sender: string): 'HUMAN' | 'AGENT' | 'SYSTEM' {
  if (sender === 'human' || sender.startsWith('human:')) return 'HUMAN';
  if (sender === 'system') return 'SYSTEM';
  return 'AGENT';
}

export function toQhorusMessage(entry: Partial<TimelineEntry>): QhorusMessage {
  const isEvent = entry.type === 'EVENT';
  const sender = isEvent ? (entry.agent_id || 'system') : (entry.sender || 'unknown');
  const rawType = isEvent ? 'EVENT' : (entry.message_type || 'status');

  return {
    id: String(entry.id ?? 0),
    channelId: '',
    sender,
    messageType: rawType.toUpperCase() as MessageType,
    actorType: resolveActorType(sender),
    content: entry.content ?? '',
    topic: '',
    replyCount: 0,
    artefactRefs: [],
    createdAt: entry.created_at || new Date().toISOString(),
  };
}

export function toQhorusChannel(info: Partial<ChannelInfo> & { name: string }): QhorusChannel {
  const allowedTypes = info.allowedTypes
    ? info.allowedTypes.split(',').map(t => t.trim().toUpperCase() as MessageType)
    : undefined;

  return {
    id: info.name,
    name: info.name,
    semantic: 'APPEND',
    paused: false,
    allowedTypes,
  };
}

export function formatEventContent(entry: Partial<TimelineEntry>): string | undefined {
  if (entry.type !== 'EVENT') return undefined;

  const parts: string[] = [];
  if (entry.tool_name) parts.push(entry.tool_name);
  if (entry.duration_ms != null) parts.push(`${entry.duration_ms}ms`);
  if (entry.token_count != null) parts.push(`${entry.token_count} tok`);

  return parts.length > 0 ? parts.join(' · ') : '—';
}
