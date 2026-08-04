import { LitElement, html, css } from 'lit';
import { customElement } from 'lit/decorators.js';
import { caseInstanceType } from '@casehubio/blocks-ui-case-explorer';
import type { EntityTypeRegistration } from '@casehubio/blocks-ui-case-explorer';
import '@casehubio/blocks-ui-case-explorer';
import { fetchWithAuth } from '../util/auth';

const CASE_TYPE: EntityTypeRegistration = {
  ...caseInstanceType({ listEndpoint: '/api/cases' }),
  reader: {
    id: (c: any) => c.id,
    summary: (c: any) => c.definitionName ?? 'unknown',
    status: (c: any) => c.status,
  },
};

@customElement('claudony-case-browser')
export class ClaudonyCaseBrowser extends LitElement {
  static override styles = css`
    :host { display: block; height: 100%; }
  `;

  override render() {
    return html`
      <blocks-case-explorer
        .entityTypes=${[CASE_TYPE]}
        .fetchFn=${fetchWithAuth}
      ></blocks-case-explorer>
    `;
  }
}
