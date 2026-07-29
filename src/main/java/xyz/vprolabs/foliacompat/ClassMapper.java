package xyz.vprolabs.foliacompat;

import org.bukkit.Bukkit;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public final class ClassMapper {
    public static final Logger log = Logger.getLogger("FoliaCompat");

    // ObfHelper state
    public static volatile boolean obfHelperResolved;
    public static volatile Map<String, Object> obfMappingsMap;
    public static volatile Object obfHelper;
    public static volatile Method obfMappingsMethod;
    public static volatile Method classMappingMojangNameMethod;

    // reobf.tiny mapping state
    public static volatile Map<String, String> spigotToMojangMap;
    public static volatile boolean mappingFileResolved;

    // CraftBukkit rename state
    public static final ConcurrentHashMap<String, String> craftBukkitRenameMap = new ConcurrentHashMap<>();
    public static volatile boolean cbRenameResolved;
    public static volatile Class<?> craftBukkitRedirectClass;

    // IMappingFile state
    public static volatile Object cachedIMappingFile;
    public static volatile Method iMappingFileRemapClassMethod;

    public static String craftBukkitVersion;
    private static boolean debugMode;

    private ClassMapper() {}

    public static void setDebugMode(boolean debug) {
        debugMode = debug;
    }

    public static boolean isDebugMode() {
        return debugMode;
    }

    public static String mapClassName(String className) {
        if (className == null) {
            log.warning("FC MAPCLASS null input");
            return null;
        }
        if (cachedIMappingFile == null || iMappingFileRemapClassMethod == null) return null;
        try {
            Object result = iMappingFileRemapClassMethod.invoke(
                cachedIMappingFile, className.replace('.', '/'));
            return (String) result;
        } catch (InvocationTargetException e) {
            log.fine("FC MAPCLASS invoke failed: " + e.getCause());
            return null;
        } catch (IllegalAccessException e) {
            log.fine("FC MAPCLASS access denied: " + e.getMessage());
            return null;
        } catch (ClassCastException e) {
            log.fine("FC MAPCLASS type mismatch: " + e.getMessage());
            return null;
        } catch (NullPointerException e) {
            log.fine("FC MAPCLASS null during reflection: " + e.getMessage());
            return null;
        }
    }

    public static String detectCraftBukkitVersion() {
        if (craftBukkitVersion != null) return craftBukkitVersion;
        if (Bukkit.getServer() == null) {
            craftBukkitVersion = "v1_21_R3";
            return craftBukkitVersion;
        }
        try {
            String name = Bukkit.getServer().getClass().getName();
            if (name == null) throw new NullPointerException("server class name is null");
            int cbPrefix = name.indexOf("org.bukkit.craftbukkit.");
            if (cbPrefix >= 0) {
                int start = cbPrefix + "org.bukkit.craftbukkit.".length();
                int end = name.indexOf('.', start);
                if (end > start) {
                    craftBukkitVersion = name.substring(start, end);
                    return craftBukkitVersion;
                }
            }
        } catch (SecurityException e) {
            log.fine("FC CBVERS security: " + e.getMessage());
        } catch (NullPointerException e) {
            log.fine("FC CBVERS null: " + e.getMessage());
        }
        ClassLoader cl = null;
        try {
            cl = Bukkit.getServer().getClass().getClassLoader();
        } catch (SecurityException e) {
            log.fine("FC CBVERS loader security: " + e.getMessage());
        }
        if (cl == null) {
            try {
                cl = ClassMapper.class.getClassLoader();
            } catch (SecurityException e) {
                log.fine("FC CBVERS own loader denied: " + e.getMessage());
            }
        }
        String[] candidates = {
            "v1_21_R3", "v1_21_R2", "v1_21_R1",
            "v1_20_R4", "v1_20_R3", "v1_20_R2", "v1_20_R1",
            "v1_19_R3", "v1_19_R2", "v1_19_R1",
            "v1_18_R2", "v1_18_R1"
        };
        if (cl != null) {
            for (String cv : candidates) {
                try {
                    Class.forName("org.bukkit.craftbukkit." + cv + ".CraftServer", false, cl);
                    craftBukkitVersion = cv;
                    return craftBukkitVersion;
                } catch (ClassNotFoundException e) {
                    // continue
                } catch (SecurityException e) {
                    log.fine("FC CBVERS security " + cv + ": " + e.getMessage());
                }
            }
        }
        craftBukkitVersion = "v1_21_R3";
        return craftBukkitVersion;
    }

    // Delegation — PaperRemapperBridge calls these via ClassMapper
    public static void resolveObfHelper() { ObfHelperBridge.resolveObfHelper(); }
    public static void resolveMappingFile() { MappingFileResolver.resolveMappingFile(); }
    public static void resolveCraftBukkitFallbacks() { CraftBukkitMapper.resolveCraftBukkitFallbacks(); }

    // Delegation — PluginClassLoader calls these via ClassMapper
    public static String mapViaObfHelper(String s) { return ObfHelperBridge.mapViaObfHelper(s); }
    public static String mapViaMappingFile(String s) { return MappingFileResolver.mapViaMappingFile(s); }
}
