import { applyTheme } from '@casehubio/pages-ui-tokens';

export function initTheme(): void {
  applyTheme('casehub-dark');
}

export const THEME_CSS = `
  :host {
    --bg: var(--pages-neutral-1);
    --surface: var(--pages-neutral-2);
    --border: var(--pages-neutral-4);
    --text: var(--pages-neutral-11);
    --text-muted: var(--pages-neutral-8);
    --accent: var(--pages-accent-9);
    --active: var(--pages-success-9);
    --danger: var(--pages-danger-9);
    --radius: var(--pages-radius-md);
    font-family: var(--pages-font-family);
    color: var(--text);
  }
`;
