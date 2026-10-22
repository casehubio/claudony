import { describe, it, expect } from 'vitest';

describe('claudony-chat-panel', () => {
  it('should be importable', async () => {
    const mod = await import('./claudony-chat-panel.js');
    expect(mod.ClaudonyChatPanel).toBeDefined();
  });

  it('registers as claudony-chat-panel custom element', async () => {
    await import('./claudony-chat-panel.js');
    expect(customElements.get('claudony-chat-panel')).toBeDefined();
  });
});
