package xyz.vprolabs.foliacompat;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.InvalidDescriptionException;
import org.bukkit.plugin.InvalidPluginException;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginLoader;
import org.bukkit.plugin.SimplePluginManager;
import org.bukkit.plugin.java.JavaPluginLoader;
import xyz.vprolabs.foliacompat.ErrorReporter;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;

@SuppressWarnings({"removal", "deprecation"})
public final class FoliaPluginLoader {
    public static final Logger log = Logger.getLogger("FoliaCompat");
    private static FoliaPluginCache pluginCache;
    static volatile PluginLoader hostLoader;
    static volatile boolean debugMode;
    private static String pluginVersion = "1.0.0";
    static volatile Object cachedBytecodeModifier;
    static volatile java.lang.reflect.Method cachedBytecodeModifyMethod;

    private FoliaPluginLoader() {}

    public static void init(File cacheDir, boolean resetCache) {
        if (resetCache && cacheDir != null) {
            File[] cached = cacheDir.listFiles((d, n) -> n.endsWith(".jar"));
            if (cached != null) for (File f : cached)
                try { Files.deleteIfExists(f.toPath()); } catch (IOException | SecurityException ignored) {}
        }
        if (cacheDir != null) { cacheDir.mkdirs(); pluginCache = new FoliaPluginCache(cacheDir); }
        FoliaPluginCache.setCurrentFcVersion(pluginVersion);
        try {
            SimplePluginManager pm = (SimplePluginManager) Bukkit.getPluginManager();
            for (Field f : SimplePluginManager.class.getDeclaredFields()) {
                if (PluginLoader.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Object val = f.get(pm);
                    if (val instanceof PluginLoader pl) { hostLoader = pl; break; }
                }
            }
        } catch (SecurityException | IllegalStateException | IllegalArgumentException | IllegalAccessException | NullPointerException e) {
            log.fine("Could not resolve host PluginLoader: " + e.getMessage());
        }
        PaperRemapperBridge.resolveBytecodeModifier();
        BytecodePatcher.initInterfaceClasses();
    }
    public static void setPluginVersion(String v) { if (v != null && !v.isEmpty()) pluginVersion = v; }
    public static String getVersion() { return pluginVersion; }
    public static void setDebugMode(boolean debug) { debugMode = debug; DebugUtil.setDebug(debug); }
    public static void registerPlugin(Plugin plugin) { PluginRegistrar.registerPlugin(plugin); }

    public static List<ManagedPlugin> loadAll(File pluginDir) {
        List<ManagedPlugin> loaded = new ArrayList<>();
        if (pluginDir == null) { log.warning("loadAll: pluginDir is null"); return loaded; }
        File[] jars = pluginDir.listFiles((dir, name) -> name.endsWith(".jar"));
        if (jars == null || jars.length == 0) { DebugUtil.info("No jars found in " + pluginDir); return loaded; }
        LogUtil.info("Loading plugins...");

        jars = sortByDependencies(jars);

        for (File jarFile : jars) {
            try {
                String jarName = jarFile.getName();
                String pluginName = readPluginName(jarFile);
                if (pluginName != null && Bukkit.getPluginManager().getPlugin(pluginName) != null) {
                    DebugUtil.info(jarName + ": plugin '" + pluginName + "' already loaded, skipping");
                    continue;
                }
                Plugin plugin = loadPlugin(jarFile, jarName);
                if (plugin != null) {
                    ClassLoader cl = plugin.getClass().getClassLoader();
                    loaded.add(new ManagedPlugin(plugin, cl));
                } else {
                    log.warning(jarName + ": loadPlugin returned null");
                }
            } catch (Throwable e) {
                LogUtil.warn("Failed to load plugin: " + jarFile.getName() + " " + e.getClass().getSimpleName() + ": " + e.getMessage());
                ErrorReporter.report(jarFile.getName(), e);
                if (e instanceof VirtualMachineError) throw e;
            }
        }
        LogUtil.info("Loaded plugins:" + loaded.size());
        return loaded;
    }

    private static File[] sortByDependencies(File[] jars) {
        if (jars == null || jars.length <= 1) return jars;

        Map<String, DepInfo> infos = new HashMap<>();
        Map<String, File> nameToJar = new LinkedHashMap<>();

        for (File jar : jars) {
            try (JarFile jf = new JarFile(jar)) {
                JarEntry entry = jf.getJarEntry("plugin.yml");
                if (entry == null) entry = jf.getJarEntry("plugin.yaml");
                if (entry == null) { nameToJar.put(jar.getName(), jar); continue; }
                try (InputStream in = jf.getInputStream(entry)) {
                    PluginDescriptionFile desc = new PluginDescriptionFile(in);
                    String name = desc.getName();
                    if (name == null || name.isEmpty()) { nameToJar.put(jar.getName(), jar); continue; }
                    List<String> deps = desc.getDepend();
                    List<String> soft = desc.getSoftDepend();
                    infos.put(name, new DepInfo(
                        deps != null ? new ArrayList<>(deps) : new ArrayList<>(),
                        soft != null ? new ArrayList<>(soft) : new ArrayList<>()));
                    nameToJar.put(name, jar);
                }
            } catch (Exception e) {
                nameToJar.put(jar.getName(), jar);
            }
        }

        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> forward = new HashMap<>();
        for (String name : infos.keySet()) {
            inDegree.put(name, 0);
            forward.put(name, new ArrayList<>());
        }
        for (Map.Entry<String, DepInfo> e : infos.entrySet()) {
            String name = e.getKey();
            for (String dep : e.getValue().depends) {
                if (!infos.containsKey(dep)) continue;
                forward.computeIfAbsent(dep, k -> new ArrayList<>()).add(name);
                inDegree.merge(name, 1, Integer::sum);
            }
        }

        List<String> sorted = new ArrayList<>();
        List<String> queue = new ArrayList<>();
        for (Map.Entry<String, Integer> e : inDegree.entrySet()) {
            if (e.getValue() == 0) queue.add(e.getKey());
        }

        while (!queue.isEmpty()) {
            String node = queue.remove(0);
            sorted.add(node);
            List<String> deps = forward.getOrDefault(node, List.of());
            for (String dep : deps) {
                int deg = inDegree.get(dep) - 1;
                inDegree.put(dep, deg);
                if (deg == 0) queue.add(dep);
            }
        }

        Set<String> cycleNames = new HashSet<>();
        for (Map.Entry<String, Integer> e : inDegree.entrySet()) {
            if (e.getValue() > 0) cycleNames.add(e.getKey());
        }
        if (!cycleNames.isEmpty()) {
            log.warning("FC DEPCYCLE detected in " + cycleNames + " — loading cyclic plugins in definition order");
            sorted.addAll(cycleNames);
        }

        Set<String> seen = new LinkedHashSet<>();
        for (String name : sorted) {
            File jf = nameToJar.remove(name);
            if (jf != null) seen.add(jf.getAbsolutePath());
        }
        seen.addAll(nameToJar.values().stream().map(File::getAbsolutePath).toList());

        List<File> result = new ArrayList<>();
        for (String path : seen) result.add(new File(path));
        return result.toArray(new File[0]);
    }

    private static record DepInfo(List<String> depends, List<String> softDepends) {}

    static Plugin loadPlugin(File jarFile, String jarName) {
        if (jarFile == null) { log.warning("loadPlugin: jarFile is null"); return null; }

        PluginDescriptionFile desc = null;
        String mainClass = null;
        String pluginName;
        try (JarFile jf = new JarFile(jarFile)) {
            JarEntry entry = jf.getJarEntry("plugin.yml");
            if (entry == null) entry = jf.getJarEntry("plugin.yaml");
            if (entry == null) throw new IllegalArgumentException("No plugin.yml in " + jarFile.getName());
            try (InputStream in = jf.getInputStream(entry)) { desc = new PluginDescriptionFile(in); }
            pluginName = desc.getName();
            mainClass = desc.getMain();
        } catch (IOException | IllegalArgumentException | InvalidDescriptionException | NullPointerException e) {
            log.warning(jarName + ": cannot parse plugin.yml: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            return null;
        }
        if (mainClass == null || mainClass.isEmpty()) { log.warning(jarName + ": no main class"); return null; }
        if (pluginName == null || pluginName.isEmpty()) pluginName = jarFile.getName().replaceAll("\\.jar$", "");
        DebugUtil.info(jarName + ": parsed plugin.yml, main=" + mainClass + ", name=" + pluginName + ", version=" + (desc != null ? desc.getVersion() : "?"));

        Plugin plugin = null;
        if (pluginCache != null) {
            try {
                File cachedJar = pluginCache.getCachedJar(jarFile);
                if (cachedJar != null) {
                    try {
                        plugin = loadWithJavaPluginLoader(cachedJar);
                        DebugUtil.info("FC PATH " + jarName + ": cached (JavaPluginLoader)");
                    } catch (IOException | IllegalStateException | UnsupportedOperationException | SecurityException e) {
                        DebugUtil.info("FC PATH " + jarName + ": cache failed (" + e.getClass().getSimpleName() + "), re-patching...");
                    }
                } else { DebugUtil.info("FC PATH " + jarName + ": cache miss, using URLClassLoader"); }
            } catch (Exception e) { log.fine(jarName + ": cache check error: " + e.getClass().getSimpleName()); }
        }

        if (plugin == null) {
            try {
                if (pluginCache != null) {
                    File patchedJar = pluginCache.createCachedJar(jarFile);
                    PluginRegistrar.copyToMainPlugins(jarFile, patchedJar);
                    java.nio.file.Path remappedJar = PaperRemapperBridge.remapJarViaPaper(jarFile);
                    if (remappedJar == null) remappedJar = PaperRemapperBridge.findExistingRemappedJar(patchedJar);
                    if (remappedJar == null) remappedJar = PaperRemapperBridge.remapJarViaPaper(patchedJar);
                    File loadFrom = (remappedJar != null) ? remappedJar.toFile() : jarFile;
                    plugin = PluginClassLoader.loadPlugin(loadFrom, jarName, desc);
                    if (remappedJar != null) DebugUtil.info(jarName + ": loaded from paper-remapped jar");
                } else {
                    plugin = PluginClassLoader.loadPlugin(jarFile, jarName, desc);
                }
            } catch (InvocationTargetException e) {
                Throwable root = e.getCause() != null ? e.getCause() : e;
                log.warning(jarName + ": load failed (" + root.getClass().getName() + ": " + root.getMessage() + ")");
                ErrorReporter.report(jarName, e);
            } catch (Exception e) {
                log.warning(jarName + ": all load methods failed (" + e.getClass().getName() + ": " + e.getMessage() + ")");
                ErrorReporter.report(jarName, e);
            }
        }
        return plugin;
    }

    private static Plugin loadWithJavaPluginLoader(File jarFile) throws IOException, IllegalStateException, UnsupportedOperationException, InvalidPluginException {
        if (jarFile == null) throw new IllegalArgumentException("jarFile must not be null");
        if (!(hostLoader instanceof JavaPluginLoader jpl)) throw new UnsupportedOperationException("hostLoader is not a JavaPluginLoader");
        Plugin p = jpl.loadPlugin(jarFile);
        if (p == null) throw new IllegalStateException("JavaPluginLoader.loadPlugin returned null");
        return p;
    }

    static String safePluginName(String raw) {
        if (raw == null || raw.isEmpty()) return "UnknownPlugin";
        return raw.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }

    static String readPluginName(File jarFile) {
        if (jarFile == null || !jarFile.isFile()) return null;
        try (JarFile jf = new JarFile(jarFile)) {
            JarEntry entry = jf.getJarEntry("plugin.yml");
            if (entry == null) entry = jf.getJarEntry("plugin.yaml");
            if (entry == null) return null;
            try (InputStream in = jf.getInputStream(entry)) { return new PluginDescriptionFile(in).getName(); }
        } catch (IOException | IllegalArgumentException | InvalidDescriptionException e) { return null; }
    }

    static <T> T callWithTCCL(Callable<T> callable, ClassLoader cl) throws Exception {
        if (callable == null) throw new IllegalArgumentException("callable must not be null");
        Thread current = Thread.currentThread();
        ClassLoader old = current.getContextClassLoader();
        current.setContextClassLoader(cl);
        try { return callable.call(); } finally { current.setContextClassLoader(old); }
    }
}
