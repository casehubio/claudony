import { loadSite, registerPanel } from "@casehubio/pages-runtime";
import { hostPanel, tabs } from "@casehubio/pages-ui";
import { initTheme } from "./theme";
import "./components/session-panel";
import "./components/claudony-fleet-panel";
import "./components/claudony-mesh-panel";
import "./components/claudony-case-browser";
import "./components/claudony-action-inbox";

initTheme();

registerPanel("session-panel", "claudony-session-panel");
registerPanel("fleet-panel", "claudony-fleet-panel");
registerPanel("mesh-panel", "claudony-mesh-panel");
registerPanel("case-browser", "claudony-case-browser");
registerPanel("action-inbox", "claudony-action-inbox");

const app = tabs(
  ["Sessions", hostPanel("session-panel")],
  ["Cases", hostPanel("case-browser")],
  ["Inbox", hostPanel("action-inbox")],
  ["Fleet", hostPanel("fleet-panel")],
  ["Mesh", hostPanel("mesh-panel")],
);

const container = document.getElementById("app");
if (container) {
  loadSite(container, app).catch(console.error);
}
