package xyz.vprolabs.foliacompat;

import java.util.logging.Level;
import java.util.logging.Logger;

public final class LogUtil {
    private static final Logger log = Logger.getLogger("FoliaCompat");
    private LogUtil() {}

    public static void info(String msg) { log.info(msg); }
    public static void warn(String msg) { log.warning(msg); }
    public static void error(String msg) { log.severe(msg); }
    public static void error(String msg, Throwable t) { log.log(Level.SEVERE, msg, t); }
}
