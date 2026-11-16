import CodeMirror from "codemirror";
import "codemirror/lib/codemirror.css";
import "codemirror/mode/javascript/javascript";
import "codemirror/mode/properties/properties";
import "codemirror/mode/toml/toml";
import "codemirror/mode/yaml/yaml";

function modeForPath(path: string): string | null {
  const name = path.toLowerCase();
  if (name.endsWith(".yml") || name.endsWith(".yaml")) return "yaml";
  if (name.endsWith(".json") || name.endsWith(".json.gz")) return "application/json";
  if (name.endsWith(".properties")) return "properties";
  if (name.endsWith(".toml")) return "toml";
  if (name.endsWith(".js")) return "javascript";
  return null;
}

function plainTextarea(id: string): HTMLTextAreaElement | null {
  const node = document.getElementById(id);
  return node instanceof HTMLTextAreaElement ? node : null;
}

export function installFileEditors() {
  const editors = new Map<string, CodeMirror.EditorFromTextArea>();
  for (const textarea of document.querySelectorAll<HTMLTextAreaElement>("textarea[data-editor]")) {
    if (!textarea.id || editors.has(textarea.id)) continue;
    try {
      const editor = CodeMirror.fromTextArea(textarea, {
        lineNumbers: true,
        lineWrapping: false,
        viewportMargin: 10,
        inputStyle: "textarea",
      });
      const label = textarea.getAttribute("aria-label");
      if (label) editor.getInputField().setAttribute("aria-label", label);
      editors.set(textarea.id, editor);
    } catch (error) {
      console.error(`Editor ${textarea.id} konnte nicht gestartet werden`, error);
    }
  }
  return {
    set(id: string, value: string, path: string) {
      const editor = editors.get(id);
      if (!editor) {
        const textarea = plainTextarea(id);
        if (textarea) textarea.value = value;
        return;
      }
      editor.setOption("mode", modeForPath(path));
      editor.setValue(value);
      editor.clearHistory();
      requestAnimationFrame(() => editor.refresh());
    },
    get(id: string) {
      return editors.get(id)?.getValue() ?? plainTextarea(id)?.value ?? "";
    },
    refresh(id: string) {
      const editor = editors.get(id);
      if (editor) requestAnimationFrame(() => editor.refresh());
    },
    setPath(id: string, path: string) {
      editors.get(id)?.setOption("mode", modeForPath(path));
    },
  };
}

export type FileEditors = ReturnType<typeof installFileEditors>;
