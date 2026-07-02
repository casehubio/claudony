let authModalShown = false;

export function escapeHtml(str: string): string {
  return str
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#039;");
}

export async function fetchWithAuth(url: string, init?: RequestInit): Promise<Response> {
  const res = await fetch(url, init);
  if (res.status === 401 && !authModalShown) {
    authModalShown = true;
    showAuthModal();
  }
  return res;
}

function showAuthModal(): void {
  const overlay = document.createElement("div");
  overlay.style.cssText =
    "position:fixed;inset:0;background:rgba(0,0,0,0.6);display:flex;align-items:center;justify-content:center;z-index:9999";

  const box = document.createElement("div");
  box.style.cssText =
    "background:#252526;padding:2rem;border-radius:8px;color:#ccc;text-align:center;max-width:320px";

  const heading = box.appendChild(document.createElement("h3"));
  heading.textContent = "Session expired";
  heading.style.cssText = "margin:0 0 1rem";

  const link = box.appendChild(document.createElement("a"));
  link.href = "/auth/login";
  link.textContent = "Log in";
  link.style.cssText = "color:#007acc";

  overlay.appendChild(box);
  document.body.appendChild(overlay);
}
