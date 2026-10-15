export interface DashboardUi {
  confirm: (options: ConfirmOptions) => Promise<boolean>;
  prompt: (options: PromptOptions) => Promise<string | null>;
  navigate: (tab: string) => void;
  notify: (message: string, error?: boolean) => void;
}

export interface ConfirmOptions {
  title: string;
  description: string;
  confirmLabel?: string;
  destructive?: boolean;
}

export interface PromptOptions extends ConfirmOptions {
  placeholder?: string;
  initialValue?: string;
  requiredValue?: string;
}

declare global {
  interface Window {
    dashboardUi?: DashboardUi;
    tab?: (tab: string) => void;
  }
}

export async function dashboardRequest<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const headers = new Headers(options.headers);
  headers.set("X-FL", "1");
  if (options.body && typeof options.body === "string") {
    headers.set("Content-Type", "application/json");
  }

  const url = new URL(path, window.location.origin);

  const response = await fetch(url, {
    ...options,
    headers,
    credentials: "same-origin",
  });
  const contentType = response.headers.get("content-type") ?? "";
  const isJson = contentType.includes("application/json");
  const result: unknown = isJson ? await response.json() : await response.text();

  if (response.status === 401) {
    window.dispatchEvent(new Event("dashboard:unauthorized"));
  }
  if (!response.ok) {
    const message =
      typeof result === "object" && result !== null && "error" in result
        ? String(result.error)
        : String(result || response.statusText);
    throw new Error(message);
  }
  if (!isJson) {
    throw new Error("Der Server hat keine JSON-Antwort für diese API-Anfrage geliefert.");
  }

  return result as T;
}

export function jsonBody(value: unknown): string {
  return JSON.stringify(value);
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 ** 2) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 ** 3) return `${(bytes / 1024 ** 2).toFixed(1)} MB`;
  return `${(bytes / 1024 ** 3).toFixed(2)} GB`;
}
