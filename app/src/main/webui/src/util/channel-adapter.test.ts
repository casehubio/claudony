import { describe, it, expect } from 'vitest';
import { toQhorusMessage, toQhorusChannel, formatEventContent, toCommitmentRecord, toCommitmentMap } from './channel-adapter';
import type { TimelineEntry } from './channel-adapter';

describe('toQhorusMessage', () => {
  it('maps a regular message with all fields', () => {
    const entry = {
      id: 42,
      message_type: 'command',
      sender: 'agent:claude',
      content: 'Run the tests',
      created_at: '2026-07-16T10:00:00Z',
    };
    const msg = toQhorusMessage(entry);
    expect(msg.id).toBe('42');
    expect(msg.channelId).toBe('');
    expect(msg.sender).toBe('agent:claude');
    expect(msg.messageType).toBe('COMMAND');
    expect(msg.actorType).toBe('AGENT');
    expect(msg.content).toBe('Run the tests');
    expect(msg.createdAt).toBe('2026-07-16T10:00:00Z');
    expect(msg.replyCount).toBe(0);
    expect(msg.artefactRefs).toEqual([]);
  });

  it('maps a human sender', () => {
    const msg = toQhorusMessage({ sender: 'human', message_type: 'query' });
    expect(msg.actorType).toBe('HUMAN');
    expect(msg.sender).toBe('human');
  });

  it('maps human:username sender as HUMAN', () => {
    const msg = toQhorusMessage({ sender: 'human:alice', message_type: 'response' });
    expect(msg.actorType).toBe('HUMAN');
    expect(msg.sender).toBe('human:alice');
  });

  it('maps EVENT type entries', () => {
    const entry = {
      id: 99,
      type: 'EVENT',
      agent_id: 'reviewer-1',
      tool_name: 'ide_find_references',
      duration_ms: 340,
      token_count: 1200,
      created_at: '2026-07-16T10:05:00Z',
    };
    const msg = toQhorusMessage(entry);
    expect(msg.messageType).toBe('EVENT');
    expect(msg.actorType).toBe('AGENT');
    expect(msg.sender).toBe('reviewer-1');
    expect(msg.content).toBe('');
  });

  it('uppercases message_type', () => {
    const msg = toQhorusMessage({ message_type: 'handoff' });
    expect(msg.messageType).toBe('HANDOFF');
  });

  it('defaults missing fields', () => {
    const msg = toQhorusMessage({});
    expect(msg.id).toBe('0');
    expect(msg.sender).toBe('unknown');
    expect(msg.messageType).toBe('STATUS');
    expect(msg.actorType).toBe('AGENT');
    expect(msg.content).toBe('');
    expect(msg.topic).toBe('');
    expect(msg.createdAt).toBeTruthy();
  });

  it('handles null content', () => {
    const msg = toQhorusMessage({ content: null });
    expect(msg.content).toBe('');
  });
});

describe('toQhorusChannel', () => {
  it('maps a channel with all fields', () => {
    const info = { name: 'case-abc/work', message_count: 15, allowedTypes: 'COMMAND,QUERY' };
    const ch = toQhorusChannel(info);
    expect(ch.id).toBe('case-abc/work');
    expect(ch.name).toBe('case-abc/work');
    expect(ch.semantic).toBe('APPEND');
    expect(ch.paused).toBe(false);
    expect(ch.allowedTypes).toEqual(['COMMAND', 'QUERY']);
  });

  it('handles null allowedTypes', () => {
    const ch = toQhorusChannel({ name: 'test-ch', allowedTypes: null });
    expect(ch.allowedTypes).toBeUndefined();
  });

  it('handles missing allowedTypes', () => {
    const ch = toQhorusChannel({ name: 'test-ch' });
    expect(ch.allowedTypes).toBeUndefined();
  });
});

describe('formatEventContent', () => {
  it('formats all three fields', () => {
    const entry = { type: 'EVENT', tool_name: 'ide_search', duration_ms: 120, token_count: 500 };
    expect(formatEventContent(entry)).toBe('ide_search · 120ms · 500 tok');
  });

  it('formats with only tool_name', () => {
    const entry = { type: 'EVENT', tool_name: 'grep' };
    expect(formatEventContent(entry)).toBe('grep');
  });

  it('formats with tool_name and duration only', () => {
    const entry = { type: 'EVENT', tool_name: 'build', duration_ms: 3400 };
    expect(formatEventContent(entry)).toBe('build · 3400ms');
  });

  it('returns fallback for empty EVENT', () => {
    const entry = { type: 'EVENT' };
    expect(formatEventContent(entry)).toBe('—');
  });

  it('returns undefined for non-EVENT entries', () => {
    const entry = { message_type: 'command', content: 'hello' };
    expect(formatEventContent(entry)).toBeUndefined();
  });
});

describe('toQhorusMessage enriched fields', () => {
  it('maps inReplyTo from timeline entry', () => {
    const entry: Partial<TimelineEntry> = {
      id: 1, sender: 'agent-1', content: 'reply',
      message_type: 'RESPONSE', in_reply_to: 42,
      correlation_id: 'corr-1', created_at: '2026-01-01T00:00:00Z',
    };
    const msg = toQhorusMessage(entry);
    expect(msg.inReplyTo).toBe('42');
    expect(msg.correlationId).toBe('corr-1');
  });

  it('maps artefactRefs from timeline entry', () => {
    const entry: Partial<TimelineEntry> = {
      id: 2, sender: 'agent-1', content: 'cmd',
      message_type: 'COMMAND', created_at: '2026-01-01T00:00:00Z',
      artefact_refs: [{ uri: 'doc://spec.md', type: 'DOCUMENT', label: 'Spec' }],
    };
    const msg = toQhorusMessage(entry);
    expect(msg.artefactRefs).toHaveLength(1);
    expect(msg.artefactRefs[0].uri).toBe('doc://spec.md');
  });

  it('maps target, replyCount, topic, deadline', () => {
    const entry: Partial<TimelineEntry> = {
      id: 3, sender: 'agent-1', content: 'do this',
      message_type: 'COMMAND', created_at: '2026-01-01T00:00:00Z',
      target: 'agent-2', reply_count: 5, topic: 'work',
      deadline: '2026-08-01T00:00:00Z',
    };
    const msg = toQhorusMessage(entry);
    expect(msg.target).toBe('agent-2');
    expect(msg.replyCount).toBe(5);
    expect(msg.topic).toBe('work');
    expect(msg.deadline).toBe('2026-08-01T00:00:00Z');
  });

  it('defaults enriched fields when absent', () => {
    const entry: Partial<TimelineEntry> = {
      id: 4, sender: 'agent-1', content: 'hello',
      message_type: 'STATUS', created_at: '2026-01-01T00:00:00Z',
    };
    const msg = toQhorusMessage(entry);
    expect(msg.inReplyTo).toBeUndefined();
    expect(msg.correlationId).toBeUndefined();
    expect(msg.artefactRefs).toEqual([]);
    expect(msg.target).toBeUndefined();
    expect(msg.replyCount).toBe(0);
    expect(msg.topic).toBe('');
    expect(msg.deadline).toBeUndefined();
  });
});

describe('CommitmentRecord mapping', () => {
  it('maps commitment response to CommitmentRecord', () => {
    const raw = {
      id: 'uuid-1', correlationId: 'corr-1', state: 'OPEN',
      requester: 'human:alice', obligor: 'agent-1',
      expiresAt: '2026-08-01T00:00:00Z', acknowledgedAt: null,
      resolvedAt: null, createdAt: '2026-07-19T10:00:00Z',
    };
    const record = toCommitmentRecord(raw);
    expect(record.state).toBe('OPEN');
    expect(record.deadline).toBe('2026-08-01T00:00:00Z');
    expect(record.createdAt).toBe('2026-07-19T10:00:00Z');
    expect(record.updatedAt).toBe('2026-07-19T10:00:00Z');
  });

  it('updatedAt is max of resolvedAt, acknowledgedAt, createdAt', () => {
    const raw = {
      id: 'uuid-2', correlationId: 'corr-2', state: 'FULFILLED',
      requester: 'human:alice', obligor: 'agent-1',
      acknowledgedAt: '2026-07-19T11:00:00Z',
      resolvedAt: '2026-07-19T12:00:00Z',
      createdAt: '2026-07-19T10:00:00Z',
    };
    const record = toCommitmentRecord(raw);
    expect(record.updatedAt).toBe('2026-07-19T12:00:00Z');
  });

  it('toCommitmentMap keys by correlationId', () => {
    const commitments = [
      { id: 'u1', correlationId: 'c1', state: 'OPEN', createdAt: '2026-01-01T00:00:00Z' },
      { id: 'u2', correlationId: 'c2', state: 'FULFILLED', createdAt: '2026-01-02T00:00:00Z' },
    ];
    const map = toCommitmentMap(commitments);
    expect(map.size).toBe(2);
    expect(map.get('c1')?.state).toBe('OPEN');
    expect(map.get('c2')?.state).toBe('FULFILLED');
  });
});
