import { loadSite, registerPanel } from "@casehubio/pages-runtime";
import { hostPanel, columns } from "@casehubio/pages-ui";
import { initTheme } from "./theme";
import "./components/session-panel";
import "./components/claudony-fleet-panel";
import "./components/claudony-mesh-panel";

initTheme();

registerPanel("session-panel", "claudony-session-panel");
registerPanel("fleet-panel", "claudony-fleet-panel");
registerPanel("mesh-panel", "claudony-mesh-panel");

const app = columns(
  hostPanel("fleet-panel"),
  hostPanel("session-panel"),
  hostPanel("mesh-panel"),
);

const container = document.getElementById("app");
if (container) {
  loadSite(container, app).catch(console.error);
}
