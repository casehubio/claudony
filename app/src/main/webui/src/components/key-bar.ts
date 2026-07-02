const KEYS = [
  { code: "\x1b", label: "Esc" },
  { code: "\x03", label: "Ctrl+C" },
  { code: "\x04", label: "Ctrl+D" },
  { code: "\x09", label: "Tab" },
  { code: "`", label: "`" },
  { code: "|", label: "|" },
  { code: "~", label: "~" },
  { code: "\x1b[A", label: "↑" },
  { code: "\x1b[B", label: "↓" },
  { code: "\x1b[C", label: "→" },
  { code: "\x1b[D", label: "←" },
];

export class ClaudonyKeyBar extends HTMLElement {
  connectedCallback(): void {
    const isTouch = "ontouchstart" in window || navigator.maxTouchPoints > 0;
    if (!isTouch) {
      this.style.display = "none";
      return;
    }

    this.innerHTML = `
      <style>
        .key-bar {
          display: flex; gap: 4px; padding: 6px 8px;
          background: var(--surface); border-top: 1px solid var(--border);
          overflow-x: auto; flex-shrink: 0;
        }
        .key-bar::-webkit-scrollbar { display: none; }
        .key-bar button {
          background: var(--bg); color: var(--text); border: 1px solid var(--border);
          border-radius: 4px; padding: 6px 10px; font-size: 12px;
          white-space: nowrap; cursor: pointer; flex-shrink: 0;
        }
        .key-bar button:hover { background: var(--accent); color: #fff; }
      </style>
      <div class="key-bar" id="key-bar">
        ${KEYS.map(k => `<button data-code="${this.escAttr(k.code)}">${k.label}</button>`).join("")}
        <button id="compose-key-btn">Compose</button>
      </div>
    `;

    this.querySelector(".key-bar")!.addEventListener("click", (e) => {
      const btn = (e.target as HTMLElement).closest("button");
      if (!btn) return;

      const code = btn.dataset.code;
      if (code) {
        this.dispatchEvent(new CustomEvent("pages-event", {
          bubbles: true, composed: true,
          detail: { topic: "key-pressed", payload: { code } },
        }));
      } else if (btn.id === "compose-key-btn") {
        this.dispatchEvent(new CustomEvent("pages-event", {
          bubbles: true, composed: true,
          detail: { topic: "compose-requested", payload: {} },
        }));
      }
    });
  }

  private escAttr(s: string): string {
    return s.replace(/&/g, "&amp;").replace(/"/g, "&quot;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  }
}

customElements.define("claudony-key-bar", ClaudonyKeyBar);
