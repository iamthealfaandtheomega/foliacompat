package xyz.vprolabs.foliacompat;

import java.util.logging.Logger;

public final class DebugUtil {
    private static final Logger log = Logger.getLogger("FoliaCompat");
    private static volatile boolean debug;

    private DebugUtil() {}

    public static void setDebug(boolean enabled) { debug = enabled; }
    public static boolean isDebug() { return debug; }

    public static void info(String msg) {
        if (debug) log.info(msg);
        else log.fine(msg);
    }
}
