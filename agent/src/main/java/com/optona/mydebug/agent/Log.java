package com.optona.mydebug.agent;

/** Uygulama konsoluna kısa, önekli satırlar yazar. {@code -Dmydebug.verbose=true} ayrıntı açar. */
final class Log {
    static final boolean VERBOSE = Boolean.getBoolean("mydebug.verbose");

    private Log() {}

    static void info(String message) {
        System.out.println("[MyDebug] " + message);
    }

    static void debug(String message) {
        if (VERBOSE) info(message);
    }

    static void warn(String message, Throwable t) {
        info(message + (t == null ? "" : ": " + describe(t)));
        if (VERBOSE && t != null) t.printStackTrace(System.out);
    }

    static String describe(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String msg = root.getMessage();
        return root.getClass().getSimpleName() + (msg == null ? "" : " " + msg);
    }
}
