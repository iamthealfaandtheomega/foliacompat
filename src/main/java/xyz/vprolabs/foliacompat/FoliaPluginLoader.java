package xyz.vprolabs.foliacompat;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.InvalidDescriptionException;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginLoader;
import org.bukkit.plugin.SimplePluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import xyz.vprolabs.foliacompat.ErrorReporter;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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

    // All loaders we created, registered BEFORE any main class is instantiated so that
    // cross-plugin class resolution sees the full set (fallback-any in PluginClassLoader).
    static final List<PluginClassLoader> managedLoaders = new CopyOnWriteArrayList<>();
    // plugin.yml name -> loader, for named depend/softdepend resolution.
    static final Map<String, PluginClassLoader> loadersByPluginName = new ConcurrentHashMap<>();
    // Loaders whose main class failed to instantiate at load time (unresolvable native
    // deps, e.g. voicechat loads after us). Retried in FoliaCompat.onEnable().
    static final List<PreparedPlugin> failedPrepared = new CopyOnWriteArrayList<>();
    static volatile Object pluginGroup;

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
        // Warm the ConfiguredPluginClassLoader probe and build the PluginClassLoaderGroup
        // proxy once: every loader copies the group reference in its constructor.
        ConfiguredLoaderBridge.isAvailable();
        pluginGroup = ConfiguredLoaderBridge.createPluginGroup();
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

        // Phase 1: parse every plugin.yml and create the classloaders. No class loading
        // yet — the loaders must first ALL be visible to each other.
        List<PreparedPlugin> prepared = new ArrayList<>();
        for (File jarFile : jars) {
            try {
                String jarName = jarFile.getName();
                String pluginName = readPluginName(jarFile);
                if (pluginName != null && Bukkit.getPluginManager().getPlugin(pluginName) != null) {
                    DebugUtil.info(jarName + ": plugin '" + pluginName + "' already loaded, skipping");
                    continue;
                }
                PluginDescriptionFile desc = readDescription(jarFile);
                if (desc == null) { log.warning(jarName + ": cannot parse plugin.yml"); continue; }
                if (desc.getMain() == null || desc.getMain().isEmpty()) { log.warning(jarName + ": no main class"); continue; }
                if (pluginName == null || pluginName.isEmpty()) pluginName = jarFile.getName().replaceAll("\\.jar$", "");
                File loadFrom = resolveLoadFile(jarFile, jarName);
                PluginClassLoader cl = PluginClassLoader.create(loadFrom, jarName, desc);
                prepared.add(new PreparedPlugin(cl, pluginName, jarName));
            } catch (Throwable e) {
                LogUtil.warn("Failed to prepare plugin: " + jarFile.getName() + " " + e.getClass().getSimpleName() + ": " + e.getMessage());
                ErrorReporter.report(jarFile.getName(), e);
                if (e instanceof VirtualMachineError vme) throw vme;
            }
        }

        // Phase 2: register every loader, then wire named dependencies. This must happen
        // before instantiation: a plugin's main class may extend or reference classes in
        // any other plugin's jar (EssentialsXSpawn -> Essentials, WorldGuard -> WE-in-FAWE).
        for (PreparedPlugin p : prepared) {
            managedLoaders.add(p.loader);
            loadersByPluginName.put(p.pluginName, p.loader);
        }
        for (PreparedPlugin p : prepared) {
            p.loader.addNamedDependencies(loadersByPluginName);
        }

        // Phase 3: instantiate main classes. Failures go to failedPrepared for the
        // onEnable retry (their native dependency may not be loaded by the server yet).
        for (PreparedPlugin p : prepared) {
            try {
                JavaPlugin plugin = p.loader.loadFromLoader(p.jarName);
                loaded.add(new ManagedPlugin(plugin, p.loader));
                DebugUtil.info(p.jarName + ": instantiated OK");
            } catch (Throwable e) {
                LogUtil.warn("Failed to instantiate plugin: " + p.jarName + " " + e.getClass().getSimpleName() + ": " + e.getMessage());
                ErrorReporter.report(p.jarName, e);
                failedPrepared.add(p);
                if (e instanceof VirtualMachineError vme) throw vme;
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

    private static record PreparedPlugin(PluginClassLoader loader, String pluginName, String jarName) {}

    static Plugin loadPlugin(File jarFile, String jarName) {
        if (jarFile == null) { log.warning("loadPlugin: jarFile is null"); return null; }
        PluginDescriptionFile desc = readDescription(jarFile);
        if (desc == null) { log.warning(jarName + ": cannot parse plugin.yml"); return null; }
        if (desc.getMain() == null || desc.getMain().isEmpty()) { log.warning(jarName + ": no main class"); return null; }
        String pluginName = desc.getName();
        if (pluginName == null || pluginName.isEmpty()) pluginName = jarFile.getName().replaceAll("\\.jar$", "");
        try {
            File loadFrom = resolveLoadFile(jarFile, jarName);
            PluginClassLoader cl = PluginClassLoader.create(loadFrom, jarName, desc);
            // Register immediately: other plugins (and later /fc loads) must see this loader.
            managedLoaders.add(cl);
            loadersByPluginName.put(pluginName, cl);
            cl.addNamedDependencies(loadersByPluginName);
            return cl.loadFromLoader(jarName);
        } catch (Exception e) {
            log.warning(jarName + ": load failed (" + e.getClass().getName() + ": " + e.getMessage() + ")");
            ErrorReporter.report(jarName, e);
            return null;
        }
    }

    private static PluginDescriptionFile readDescription(File jarFile) {
        try (JarFile jf = new JarFile(jarFile)) {
            JarEntry entry = jf.getJarEntry("plugin.yml");
            if (entry == null) entry = jf.getJarEntry("plugin.yaml");
            if (entry == null) return null;
            try (InputStream in = jf.getInputStream(entry)) { return new PluginDescriptionFile(in); }
        } catch (IOException | IllegalArgumentException | InvalidDescriptionException | NullPointerException e) {
            return null;
        }
    }

    // Cache/remap pipeline: produces the jar to actually load from. On Folia the Paper
    // remapper and the JavaPluginLoader cache path are inert (hostLoader is null, no
    // IMappingFile), so this normally returns the original jar; the machinery is kept
    // because it is harmless and may help on hybrid setups.
    private static File resolveLoadFile(File jarFile, String jarName) {
        File loadFrom = jarFile;
        if (pluginCache == null) return loadFrom;
        try {
            File patchedJar = pluginCache.createCachedJar(jarFile);
            PluginRegistrar.copyToMainPlugins(jarFile, patchedJar);
            java.nio.file.Path remappedJar = PaperRemapperBridge.remapJarViaPaper(jarFile);
            if (remappedJar == null) remappedJar = PaperRemapperBridge.findExistingRemappedJar(patchedJar);
            if (remappedJar == null) remappedJar = PaperRemapperBridge.remapJarViaPaper(patchedJar);
            if (remappedJar != null) {
                loadFrom = remappedJar.toFile();
                DebugUtil.info(jarName + ": loaded from paper-remapped jar");
            }
        } catch (Exception e) {
            log.fine(jarName + ": cache/remap skipped (" + e.getClass().getSimpleName() + "): " + e.getMessage());
        }
        return loadFrom;
    }

    // Re-attempts instantiation for plugins that failed at load time. Run at onEnable:
    // native plugins the server loads after us (voicechat, ...) now have classes that
    // our loadClass can reach through the Bukkit plugin manager.
    public static List<ManagedPlugin> retryFailedLoads() {
        List<ManagedPlugin> loaded = new ArrayList<>();
        if (failedPrepared.isEmpty()) return loaded;
        DebugUtil.info("FC RETRY retrying " + failedPrepared.size() + " failed plugin(s)");
        for (PreparedPlugin p : failedPrepared) {
            try {
                JavaPlugin plugin = p.loader.loadFromLoader(p.jarName);
                loaded.add(new ManagedPlugin(plugin, p.loader));
                DebugUtil.info("FC RETRYOK " + p.jarName);
            } catch (Throwable e) {
                LogUtil.warn("FC RETRYFAIL " + p.jarName + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
                ErrorReporter.report(p.jarName + " (retry)", e);
            }
        }
        failedPrepared.clear();
        return loaded;
    }

    static Class<?> findClassAcrossLoaders(String name) {
        for (PluginClassLoader cl : managedLoaders) {
            try { return Class.forName(name, false, cl); } catch (Throwable ignored) {}
        }
        return null;
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
