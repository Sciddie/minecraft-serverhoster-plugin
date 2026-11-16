import { Component, type ErrorInfo, type ReactNode } from "react";

type Props = {
  children: ReactNode;
  page?: boolean;
};

type State = {
  failed: boolean;
};

export class DashboardErrorBoundary extends Component<Props, State> {
  state: State = { failed: false };

  static getDerivedStateFromError(): State {
    return { failed: true };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error("Dashboard-Bereich konnte nicht angezeigt werden", error, info.componentStack);
  }

  render() {
    if (!this.state.failed) return this.props.children;
    if (this.props.page) {
      return (
        <section className="login-screen">
          <div className="login-card">
            <p className="eyebrow">SERVERHOSTER</p>
            <h1>Oberfläche nicht geladen</h1>
            <p className="login-description">Ein Teil der Seite konnte nicht starten. Der Fehler steht in der Browser-Konsole.</p>
            <button className="btn pri login-button" type="button" onClick={() => window.location.reload()}>Seite neu laden</button>
          </div>
        </section>
      );
    }
    return <p className="login-error" role="alert">Dieser Bereich konnte nicht geladen werden. Der Fehler steht in der Browser-Konsole.</p>;
  }
}
