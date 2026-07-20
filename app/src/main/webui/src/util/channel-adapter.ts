import type { QhorusMessage, QhorusChannel, MessageType, ArtefactRef, CommitmentState } from '@casehubio/blocks-ui-channel-activity';

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
  in_reply_to?: number | null;
  correlation_id?: string;
  artefact_refs?: ArtefactRef[] | null;
  target?: string | null;
  reply_count?: number;
  deadline?: string | null;
  topic?: string | null;
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
    topic: entry.topic ?? '',
    correlationId: entry.correlation_id,
    inReplyTo: entry.in_reply_to ? String(entry.in_reply_to) : undefined,
    artefactRefs: entry.artefact_refs ?? [],
    target: entry.target ?? undefined,
    replyCount: entry.reply_count ?? 0,
    deadline: entry.deadline ?? undefined,
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

export interface CommitmentRecord {
  readonly state: CommitmentState;
  readonly deadline?: string;
  readonly acknowledgedAt?: string;
  readonly createdAt: string;
  readonly updatedAt: string;
}

interface RawCommitment {
  id: string;
  correlationId: string;
  state: string;
  requester?: string;
  obligor?: string;
  expiresAt?: string | null;
  acknowledgedAt?: string | null;
  resolvedAt?: string | null;
  createdAt?: string | null;
}

export function toCommitmentRecord(raw: RawCommitment): CommitmentRecord {
  const timestamps = [raw.resolvedAt, raw.acknowledgedAt, raw.createdAt]
    .filter((t): t is string => t != null);
  const updatedAt = timestamps.length > 0
    ? timestamps.reduce((a, b) => a > b ? a : b)
    : raw.createdAt ?? new Date().toISOString();

  return {
    state: raw.state as CommitmentState,
    deadline: raw.expiresAt ?? undefined,
    acknowledgedAt: raw.acknowledgedAt ?? undefined,
    createdAt: raw.createdAt ?? new Date().toISOString(),
    updatedAt,
  };
}

export function toCommitmentMap(
  commitments: RawCommitment[]
): Map<string, CommitmentRecord> {
  const map = new Map<string, CommitmentRecord>();
  for (const c of commitments) {
    map.set(c.correlationId, toCommitmentRecord(c));
  }
  return map;
}
