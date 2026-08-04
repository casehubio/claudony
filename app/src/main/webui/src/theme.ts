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
  @media (max-width: 767px) {
    :host {
      --pages-font-size-xs: 9px;
      --pages-font-size-sm: 10px;
      --pages-font-size-base: 12px;
      --pages-font-size-lg: 13px;
      --pages-font-size-xl: 16px;
      --pages-font-size-2xl: 18px;
    }
  }
  @media (min-width: 1440px) {
    :host {
      --pages-font-size-xs: 11px;
      --pages-font-size-sm: 12px;
      --pages-font-size-base: 14px;
      --pages-font-size-lg: 15px;
      --pages-font-size-xl: 20px;
      --pages-font-size-2xl: 22px;
    }
  }
`;
