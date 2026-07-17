import { loadSite, registerPanel } from "@casehubio/pages-runtime";
import { hostPanel, rows } from "@casehubio/pages-ui";
import { initTheme } from "./theme";
import "./components/session-grid";

initTheme();

registerPanel("session-grid", "claudony-session-grid");

const app = rows(
  hostPanel("session-grid"),
);

const container = document.getElementById("app");
if (container) {
  loadSite(container, app).catch(console.error);
}
