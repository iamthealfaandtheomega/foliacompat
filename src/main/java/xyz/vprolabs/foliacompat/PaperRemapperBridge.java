package xyz.vprolabs.foliacompat;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public final class PaperRemapperBridge {
    private static final Logger log = Logger.getLogger("FoliaCompat");

    private PaperRemapperBridge() {}

    static Path remapJarViaPaper(File jarFile) {
        if (jarFile == null) return null;
        try {
            Class<?> mgrClass = Class.forName("io.papermc.paper.plugin.PluginInitializerManager");
            Method instanceMethod = mgrClass.getMethod("instance");
            Object mgr = instanceMethod.invoke(null);
            Field remapperField = mgrClass.getField("pluginRemapper");
            Object remapper = remapperField.get(mgr);
            if (remapper == null) { log.fine("Paper remapper is null (remapping disabled)"); return null; }
            Method rewriteMethod = remapper.getClass().getMethod("rewritePlugin", Path.class);
            Path result = (Path) rewriteMethod.invoke(remapper, jarFile.toPath());
            if (result != null) DebugUtil.info("Paper remapper produced: " + result);
            return result;
        } catch (ClassNotFoundException | NoSuchMethodException | NoSuchFieldException
                | SecurityException | IllegalAccessException | IllegalArgumentException e) {
            log.fine("Paper remapper not available: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            return null;
        } catch (InvocationTargetException e) {
            log.fine("Paper remapper invocation failed: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
            return null;
        }
    }

    static Path findExistingRemappedJar(File jarFile) {
        if (jarFile == null) return null;
        Path remappedDir = Path.of("plugins", ".paper-remapped", "unknown-origin");
        if (!Files.isDirectory(remappedDir)) return null;

        String baseName = jarFile.getName();
        if (baseName.endsWith(".jar")) baseName = baseName.substring(0, baseName.length() - 4);

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(remappedDir, baseName + "-*.jar")) {
            for (Path p : stream) { DebugUtil.info("Found existing remapped jar: " + p); return p; }
        } catch (IOException | SecurityException e) {
            log.finest("findExistingRemappedJar: " + e.getClass().getSimpleName());
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(remappedDir, "*.jar")) {
            for (Path p : stream) {
                String fn = p.getFileName().toString();
                if (fn.contains(baseName) || baseName.contains(fn.replaceAll("-\\d+\\.jar$", ""))) {
                    DebugUtil.info("Found existing remapped jar (broad): " + p);
                    return p;
                }
            }
        } catch (IOException | SecurityException e) {
            log.finest("findExistingRemappedJar broad: " + e.getClass().getSimpleName());
        }
        return null;
    }

    static void resolveBytecodeModifier() {
        DebugUtil.info("FC INIT resolveBytecodeModifier start");

        // Path A: Paper's ClassloaderBytecodeModifier
        try {
            Class<?> modClass = Class.forName("io.papermc.paper.plugin.remapping.ClassloaderBytecodeModifier");
            Method instanceMethod = modClass.getMethod("bytecodeModifier");
            Object modifier = instanceMethod.invoke(null);
            if (modifier != null) {
                Method modifyMethod = modifier.getClass().getMethod("modify", ClassLoader.class, byte[].class);
                FoliaPluginLoader.cachedBytecodeModifier = modifier;
                FoliaPluginLoader.cachedBytecodeModifyMethod = modifyMethod;
                DebugUtil.info("Paper ClassloaderBytecodeModifier resolved");
            }
        } catch (ClassNotFoundException | NoSuchMethodException | SecurityException
                | IllegalAccessException | IllegalArgumentException e) {
            if (FoliaPluginLoader.debugMode) log.fine("ClassloaderBytecodeModifier not available: " + e.getClass().getSimpleName());
        } catch (InvocationTargetException e) {
            if (FoliaPluginLoader.debugMode) log.fine("ClassloaderBytecodeModifier invocation failed: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
        }

        // Path B: Extract IMappingFile from PluginRemapper
        try {
            Class<?> mgrClass = Class.forName("io.papermc.paper.plugin.PluginInitializerManager");
            Method instanceMethod = mgrClass.getMethod("instance");
            Object mgr = instanceMethod.invoke(null);
            Field remapperField = mgrClass.getField("pluginRemapper");
            Object pluginRemapper = remapperField.get(mgr);

            if (pluginRemapper != null && extractMappingFile(pluginRemapper)) return;
        } catch (ClassNotFoundException | NoSuchMethodException | NoSuchFieldException
                | SecurityException | IllegalAccessException | IllegalArgumentException e) {
            if (FoliaPluginLoader.debugMode) log.fine("IMappingFile not available: " + e.getClass().getSimpleName());
        } catch (InvocationTargetException e) {
            if (FoliaPluginLoader.debugMode) log.fine("IMappingFile invocation failed: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
        }

        // Fallbacks: ObfHelper, reobf.tiny, CraftBukkit
        try { ClassMapper.resolveObfHelper(); } catch (Exception e) {
            log.warning("FC FAIL resolveObfHelper: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
        try { ClassMapper.resolveMappingFile(); } catch (Exception e) {
            log.warning("FC FAIL resolveMappingFile: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
        try { ClassMapper.resolveCraftBukkitFallbacks(); } catch (Exception e) {
            log.warning("FC FAIL resolveCraftBukkitFallbacks: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

    private static boolean extractMappingFile(Object pluginRemapper) {
        for (Field f : pluginRemapper.getClass().getDeclaredFields()) {
            f.setAccessible(true);
            Object val;
            try { val = f.get(pluginRemapper); } catch (SecurityException | IllegalArgumentException | IllegalStateException | IllegalAccessException e) { continue; }
            if (val == null) continue;

            if (val instanceof CompletableFuture) {
                try {
                    Method joinMethod = CompletableFuture.class.getMethod("join");
                    Object mappingFile = joinMethod.invoke(val);
                    if (mappingFile != null && verifyMappingFile(mappingFile, f.getName())) return true;
                } catch (Exception ignored) {}
            }

            try {
                if (verifyMappingFile(val, "direct:" + f.getName())) return true;
            } catch (Exception ignored) {}
        }

        try {
            Method mappingsMethod = pluginRemapper.getClass().getDeclaredMethod("mappings");
            mappingsMethod.setAccessible(true);
            Object mappingFile = mappingsMethod.invoke(pluginRemapper);
            if (mappingFile != null && verifyMappingFile(mappingFile, "mappings()")) return true;
        } catch (Exception ignored) {}
        return false;
    }

    private static boolean verifyMappingFile(Object mappingFile, String source) {
        try {
            Method remapMethod = mappingFile.getClass().getMethod("remapClass", String.class);
            String result = (String) remapMethod.invoke(mappingFile, "net/minecraft/world/entity/player/EntityHuman");
            if (result != null && result.contains("Player")) {
                ClassMapper.cachedIMappingFile = mappingFile;
                ClassMapper.iMappingFileRemapClassMethod = remapMethod;
                DebugUtil.info("IMappingFile resolved (" + source + ")");
                return true;
            }
        } catch (NoSuchMethodException | SecurityException | IllegalAccessException
                | IllegalArgumentException | InvocationTargetException ignored) {}
        return false;
    }
}
