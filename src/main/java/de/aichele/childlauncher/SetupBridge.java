package de.aichele.childlauncher;

/** Calls WorldPorter when that plugin is installed. No compile dependency on it. */
final class SetupBridge {
    private SetupBridge() {}

    static String call(Object plugin, String method) {
        if (plugin == null) throw new IllegalStateException("WorldPorter fehlt");
        if (!method.equals("statusText") && !method.equals("preview") && !method.equals("execute"))
            throw new IllegalArgumentException(method);
        try {
            Object v = plugin.getClass().getMethod(method).invoke(plugin);
            return v == null ? "" : String.valueOf(v);
        } catch (ReflectiveOperationException e) {
            Throwable c = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException(String.valueOf(c.getMessage()));
        }
    }
}
