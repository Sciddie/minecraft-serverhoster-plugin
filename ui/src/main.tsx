import { createRoot } from "react-dom/client";
import App from "./App";
import { DashboardErrorBoundary } from "./components/DashboardErrorBoundary";
import "./index.css";

const root = document.getElementById("root");
if (!root) {
  document.body.textContent = "Dashboard-Startpunkt fehlt";
} else {
  createRoot(root).render(
    <DashboardErrorBoundary page>
      <App />
    </DashboardErrorBoundary>,
  );
}
