import CodeMirror from "codemirror";
import "codemirror/lib/codemirror.css";
import "codemirror/mode/javascript/javascript";
import "codemirror/mode/properties/properties";
import "codemirror/mode/toml/toml";
import "codemirror/mode/yaml/yaml";

type EditorId = "etext" | "config";

function modeForPath(path: string): string | null {
  const name = path.toLowerCase();
  if (name.endsWith(".yml") || name.endsWith(".yaml")) return "yaml";
  if (name.endsWith(".json") || name.endsWith(".json.gz")) return "application/json";
  if (name.endsWith(".properties")) return "properties";
  if (name.endsWith(".toml")) return "toml";
  if (name.endsWith(".js")) return "javascript";
  return null;
}

export function installFileEditors() {
  const editors = new Map<EditorId, CodeMirror.EditorFromTextArea>();
  for (const id of ["etext", "config"] as const) {
    const textarea = document.getElementById(id);
    if (!(textarea instanceof HTMLTextAreaElement)) throw new Error(`Editor ${id} is missing`);
    const editor = CodeMirror.fromTextArea(textarea, {
      lineNumbers: true,
      lineWrapping: false,
      viewportMargin: 10,
      inputStyle: "textarea",
    });
    editor.getInputField().setAttribute("aria-label", id === "etext" ? "Dateiinhalt" : "config.yml");
    editors.set(id, editor);
  }
  const editorFor = (id: EditorId) => {
    const editor = editors.get(id);
    if (!editor) throw new Error(`Editor ${id} is missing`);
    return editor;
  };
  return {
    set(id: EditorId, value: string, path: string) {
      const editor = editorFor(id);
      editor.setOption("mode", modeForPath(path));
      editor.setValue(value);
      editor.clearHistory();
      requestAnimationFrame(() => editor.refresh());
    },
    get(id: EditorId) { return editorFor(id).getValue(); },
    refresh(id: EditorId) { requestAnimationFrame(() => editorFor(id).refresh()); },
    setPath(id: EditorId, path: string) { editorFor(id).setOption("mode", modeForPath(path)); },
  };
}

export type FileEditors = ReturnType<typeof installFileEditors>;
