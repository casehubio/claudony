import { loadSite, registerPanel } from "@casehubio/pages-runtime";
import { hostPanel, columns } from "@casehubio/pages-ui";
import { initTheme } from "./theme";
import "./components/session-grid";
import "./components/claudony-fleet-panel";
import "./components/claudony-mesh-panel";

initTheme();

registerPanel("session-grid", "claudony-session-grid");
registerPanel("fleet-panel", "claudony-fleet-panel");
registerPanel("mesh-panel", "claudony-mesh-panel");

const app = columns(
  hostPanel("fleet-panel"),
  hostPanel("session-grid"),
  hostPanel("mesh-panel"),
);

const container = document.getElementById("app");
if (container) {
  loadSite(container, app).catch(console.error);
}
