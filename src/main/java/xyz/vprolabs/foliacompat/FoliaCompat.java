package xyz.vprolabs.foliacompat;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import xyz.vprolabs.foliacompat.scheduler.FoliaBukkitScheduler;
import xyz.vprolabs.foliacompat.scheduler.SchedulerBridge;
import xyz.vprolabs.foliacompat.scheduler.TaskMapper;
import xyz.vprolabs.foliacompat.ModrinthUpdateChecker;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class FoliaCompat extends JavaPlugin {

    private final CopyOnWriteArrayList<ManagedPlugin> loadedPlugins = new CopyOnWriteArrayList<>();
    private FoliaConfig foliaConfig;
    private boolean initialized;
    private Object originalScheduler;

    private void debug(String msg) { DebugUtil.info(msg); }

    private void handleError(String context, Throwable error) {
        if (error == null) return;
        if (error instanceof VirtualMachineError) throw (VirtualMachineError) error;
        ErrorReporter.report(context, error);
        if (DebugUtil.isDebug()) {
            String type = error instanceof Error ? "Error" : "Exception";
            getLogger().log(Level.WARNING, type + " detected in " + context + " — " + (foliaConfig != null && foliaConfig.isErrorReportingEnabled() ? "an anonymous error report has been sent. Disable error-reporting in config.yml." : "enable error-reporting in config.yml for error reports."), error);
        }
    }

    @Override
    public void onLoad() {
        long start = System.currentTimeMillis();
        getLogger().info("FoliaCompat initializing...");

        boolean folia;
        try { Class.forName("io.papermc.paper.threadedregions.RegionizedServer"); folia = true; }
        catch (ClassNotFoundException e) { folia = false; }

        if (!folia) {
            getLogger().info("Not a Folia server — FoliaCompat is not needed, skipping.");
            return;
        }

        getLogger().info("Server: " + Bukkit.getVersion() + " (Folia) | API: " + Bukkit.getBukkitVersion());
        getLogger().info("Java: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        getLogger().info("OS: " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));

        if (getDataFolder() == null) {
            getLogger().severe("getDataFolder() returned null — cannot initialize");
            return;
        }
        foliaConfig = new FoliaConfig(getDataFolder());
        foliaConfig.load();
        DebugUtil.setDebug(foliaConfig.isDebug());
        ErrorReporter.setEnabled(foliaConfig.isErrorReportingEnabled());
        ErrorReporter.setPluginVersion(getPluginMeta().getVersion());

        if (Bukkit.getServer() != null) {
            this.originalScheduler = Bukkit.getScheduler();
            try { injectScheduler(); } catch (Throwable t) { handleError("injectScheduler", t); }
        } else {
            getLogger().warning("Bukkit.getServer() returned null — skipping scheduler injection");
        }

        File pluginsDir = new File(getDataFolder(), "plugins");
        if (!pluginsDir.exists() && !pluginsDir.mkdirs()) {
            getLogger().warning("Could not create plugins directory: " + pluginsDir);
        }

        FoliaPluginLoader.setPluginVersion(getPluginMeta().getVersion());
        FoliaPluginLoader.init(new File(getDataFolder(), "cache"), foliaConfig.isResetCacheOnRestart());
        FoliaPluginLoader.setDebugMode(foliaConfig.isDebug());
        ClassMapper.setDebugMode(foliaConfig.isDebug());

        List<ManagedPlugin> managedPlugins = FoliaPluginLoader.loadAll(pluginsDir);

        for (ManagedPlugin mp : managedPlugins) {
            if (mp == null) { getLogger().warning("  Null ManagedPlugin entry — skipping"); continue; }
            String pname = mp.getName() != null ? mp.getName() : "Unknown";
            Plugin plugin = mp.plugin();
            if (plugin == null) { getLogger().warning("  " + pname + ": plugin is null — skipping"); continue; }
            ClassLoader cl = mp.classLoader();
            if (cl == null) { getLogger().warning("  " + pname + ": classLoader is null — skipping"); continue; }
                try {
                    debug("  Registering " + pname + "...");
                    FoliaPluginLoader.registerPlugin(plugin);
                    debug("  Calling onLoad() for " + pname + "...");
                    FoliaPluginLoader.callWithTCCL(() -> {
                        try { plugin.onLoad(); } catch (Throwable t) { throw new RuntimeException(t); }
                        return null;
                    }, cl);
                    mp.setEnabled(true);
                    debug(pname + ": LOADED OK");
                } catch (RuntimeException e) {
                    mp.setEnabled(false);
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    handleError(pname + " onLoad", cause);
                    LogUtil.warn("  " + pname + ": FAILED on load: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
                } catch (Exception e) {
                    mp.setEnabled(false);
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    handleError(pname + " onLoad", cause);
                    LogUtil.warn("  " + pname + ": FAILED on load: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
                }
            }

        this.loadedPlugins.addAll(managedPlugins);
        this.initialized = true;

        long elapsed = System.currentTimeMillis() - start;
        getLogger().info("FoliaCompat initialized in " + elapsed + "ms — loaded " + managedPlugins.size() + " plugin(s)");
    }

    @Override
    public void onEnable() {
        if (!initialized) return;
        if (!(Bukkit.getScheduler() instanceof FoliaBukkitScheduler)) {
            DebugUtil.info("FC SCHEDMISS scheduler field was reverted — re-injecting");
            try { injectScheduler(); } catch (Throwable t) { handleError("injectScheduler onEnable", t); }
        }
        getCommand("foliacompat").setExecutor(new FoliaCompatCommand(this, new File(getDataFolder(), "plugins")));
        try { new Metrics(this, 32962); } catch (Exception ignored) {}
        checkForUpdates();
        for (ManagedPlugin mp : loadedPlugins) {
            if (mp == null) continue;
            String pname = mp.getName() != null ? mp.getName() : "Unknown";
            if (mp.isEnabled()) {
                Plugin plugin = mp.plugin();
                ClassLoader cl = mp.classLoader();
                if (plugin == null || cl == null) {
                    debug("  " + pname + ": SKIPPED (plugin or classLoader is null)");
                    continue;
                }
                try {
                    debug("  Enabling " + pname + "...");
                    FoliaPluginLoader.callWithTCCL(() -> {
                        try { plugin.onEnable(); } catch (Throwable t) { throw new RuntimeException(t); }
                        return null;
                    }, cl);
                    debug("  " + pname + ": ENABLED OK");
                } catch (RuntimeException e) {
                    mp.setEnabled(false);
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    handleError(pname + " onEnable", cause);
                    LogUtil.warn("  " + pname + ": FAILED on enable: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
                } catch (Exception e) {
                    mp.setEnabled(false);
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    handleError(pname + " onEnable", cause);
                    LogUtil.warn("  " + pname + ": FAILED on enable: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
                }
            } else {
                debug("  " + pname + ": SKIPPED (disabled/errored during load)");
            }
        }
    }

    @Override
    public void onDisable() {
        if (!(Bukkit.getScheduler() instanceof FoliaBukkitScheduler)) {
            DebugUtil.info("FC SCHEDMISS re-injecting FoliaBukkitScheduler for shutdown");
            try { injectScheduler(); } catch (Throwable t) { handleError("injectScheduler onDisable", t); }
        }
        if (!initialized) return;
        for (int i = loadedPlugins.size() - 1; i >= 0; i--) {
            ManagedPlugin mp = loadedPlugins.get(i);
            if (mp == null) continue;
            String pname = mp.getName() != null ? mp.getName() : "Unknown";
            if (mp.isEnabled()) {
                Plugin plugin = mp.plugin();
                ClassLoader cl = mp.classLoader();
                if (plugin == null || cl == null) {
                    debug("  " + pname + ": SKIPPED (plugin or classLoader is null)");
                    continue;
                }
                try {
                    debug("  Disabling " + pname + "...");
                    FoliaPluginLoader.callWithTCCL(() -> {
                        try { plugin.onDisable(); } catch (Throwable t) { throw new RuntimeException(t); }
                        return null;
                    }, cl);
                    debug("  " + pname + ": DISABLED OK");
                } catch (RuntimeException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    handleError(pname + " onDisable", cause);
                    LogUtil.warn("  " + pname + ": ERROR on disable: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
                } catch (Exception e) {
                    handleError(pname + " onDisable", e);
                    LogUtil.warn("  " + pname + ": ERROR on disable: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        }
        TaskMapper.cancelAll(null);
        for (int i = loadedPlugins.size() - 1; i >= 0; i--) {
            ManagedPlugin mp = loadedPlugins.get(i);
            if (mp != null) mp.cleanup();
        }
        protectOriginalScheduler();
        if (!(Bukkit.getScheduler() instanceof FoliaBukkitScheduler)) {
            DebugUtil.info("FC SCHEDPOST scheduler was reverted to " + Bukkit.getScheduler().getClass().getName() + " — re-injecting");
            try { injectScheduler(); } catch (Throwable t) { handleError("injectScheduler shutdown", t); }
        }
        startShutdownWatcher();
        loadedPlugins.clear();
    }

    @SuppressWarnings({"removal", "deprecation"})
    private void injectScheduler() {
        Server server = Bukkit.getServer();
        if (server == null) {
            DebugUtil.info("FC SCHEDINJECT failed — Bukkit.getServer() returned null");
            return;
        }
        Object target = server;
        injectIntoSchedulerField(target, target.getClass(), "CraftServer");
        Object console = getConsoleField(server);
        if (console != null && console != server) {
            injectIntoSchedulerField(console, console.getClass(), "MinecraftServer");
        }
    }

    private void injectIntoSchedulerField(Object target, Class<?> startClass, String label) {
        Class<?> cl = startClass;
        boolean found = false;
        while (cl != null) {
            for (Field f : cl.getDeclaredFields()) {
                if (BukkitScheduler.class.isAssignableFrom(f.getType())) {
                    found = true;
                    Class<?> fieldType = f.getType();
                    DebugUtil.info("FC SCHEDFIELD " + cl.getSimpleName() + "." + f.getName()
                        + " type=" + fieldType.getSimpleName() + " final=" + Modifier.isFinal(f.getModifiers())
                        + " target=" + label);
                    FoliaBukkitScheduler scheduler = new FoliaBukkitScheduler();
                    Object value = scheduler;
                    if (!fieldType.isInterface() && !Modifier.isAbstract(fieldType.getModifiers())
                        && fieldType != BukkitScheduler.class) {
                        value = SchedulerBridge.createBridge(scheduler, fieldType);
                        DebugUtil.info("FC BRIDGE using " + value.getClass().getName() + " for field " + f.getName());
                    }
                    try {
                        f.setAccessible(true);
                        removeFinal(f);
                        f.set(target, value);
                        DebugUtil.info("FC SCHEDINJECT OK " + label + "." + f.getName() + " (reflection)");
                    } catch (Exception e1) {
                        DebugUtil.info("FC SCHEDINJECT reflect failed on " + label + "." + f.getName() + ": " + e1.getClass().getSimpleName());
                        try {
                            Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
                            uf.setAccessible(true);
                            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) uf.get(null);
                            long offset = unsafe.objectFieldOffset(f);
                            unsafe.putObject(target, offset, value);
                            DebugUtil.info("FC SCHEDINJECT OK " + label + "." + f.getName() + " (unsafe)");
                        } catch (Exception e2) {
                            DebugUtil.info("FC SCHEDINJECT unsafe also failed on " + label + "." + f.getName() + ": " + e2.getClass().getSimpleName());
                        }
                    }
                }
            }
            cl = cl.getSuperclass();
        }
        if (!found) DebugUtil.info("FC SCHEDINJECT no field in " + label + " hierarchy");
    }

    private Object getConsoleField(Server server) {
        Class<?> cl = server.getClass();
        while (cl != null) {
            try {
                for (Field f : cl.getDeclaredFields()) {
                    String name = f.getName();
                    if ("console".equals(name) || "server".equals(name)) {
                        f.setAccessible(true);
                        return f.get(server);
                    }
                }
            } catch (Exception ignored) {}
            cl = cl.getSuperclass();
        }
        return null;
    }

    private static void removeFinal(Field f) {
        if (!Modifier.isFinal(f.getModifiers())) return;
        try {
            Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            uf.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) uf.get(null);
            Field mf = Field.class.getDeclaredField("modifiers");
            long offset = unsafe.objectFieldOffset(mf);
            if (mf.getType() == long.class) {
                unsafe.putLong(f, offset, unsafe.getLong(f, offset) & ~(long) Modifier.FINAL);
            } else {
                unsafe.putInt(f, offset, unsafe.getInt(f, offset) & ~Modifier.FINAL);
            }
        } catch (Exception ignored) { /* removeFinal is best-effort; field set will fall back to Unsafe */ }
    }

    @SuppressWarnings("MemoryLeakDetector")
    private void startShutdownWatcher() {
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "FC-ShutdownWatcher");
            t.setDaemon(true);
            return t;
        });
        boolean[] warned = {false};
        exec.scheduleAtFixedRate(() -> {
            Object sched = Bukkit.getScheduler();
            if (!(sched instanceof FoliaBukkitScheduler)) {
                if (!warned[0]) {
                    DebugUtil.info("FC SCHEDWATCH scheduler was " + sched.getClass().getName() + " — re-injecting");
                    warned[0] = true;
                }
                protectSchedulerActiveWorkers();
                protectInstance(sched);
                injectScheduler();
            }
        }, 0, 200, TimeUnit.MILLISECONDS);
    }

    private Object getUnsafe() {
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return f.get(null);
        } catch (Exception e) {
            return null;
        }
    }

    private void protectInstance(Object scheduler) {
        if (scheduler == null) return;
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) getUnsafe();
        if (unsafe == null) { DebugUtil.info("FC PROTECT no Unsafe available"); return; }
        Class<?> cl = scheduler.getClass();
        while (cl != null) {
            for (Field f : cl.getDeclaredFields()) {
                try {
                    long offset = unsafe.objectFieldOffset(f);
                    Object val = unsafe.getObject(scheduler, offset);
                    if (val != null) continue;
                    Class<?> ft = f.getType();
                    Object replacement = null;
                    if (Map.class.isAssignableFrom(ft)) {
                        replacement = new java.util.concurrent.ConcurrentHashMap<>();
                    } else if (List.class.isAssignableFrom(ft)) {
                        replacement = Collections.emptyList();
                    } else if (Set.class.isAssignableFrom(ft)) {
                        replacement = Collections.emptySet();
                    } else if (Collection.class.isAssignableFrom(ft)) {
                        replacement = Collections.emptyList();
                    }
                    if (replacement != null) {
                        unsafe.putObject(scheduler, offset, replacement);
                        DebugUtil.info("FC PROTECTFIX null " + ft.getSimpleName() + " field: " + cl.getSimpleName() + "." + f.getName());
                    }
                } catch (Exception e) {
                    DebugUtil.info("FC PROTECTERR " + e.getClass().getSimpleName() + " on " + cl.getSimpleName() + " field: " + f.getName());
                }
            }
            cl = cl.getSuperclass();
        }
    }

    private boolean checkActiveWorkers(Object sched, String label) {
        try {
            java.lang.reflect.Method m = sched.getClass().getMethod("getActiveWorkers");
            Object result = m.invoke(sched);
            if (result == null) {
                DebugUtil.info("FC AWCHECK " + label + " getActiveWorkers()=null");
                return false;
            }
            int sz = (result instanceof Collection ? ((Collection) result).size() : -1);
            DebugUtil.info("FC AWCHECK " + label + " getActiveWorkers()=" + result.getClass().getSimpleName() + ",size=" + sz);
            return true;
        } catch (Exception e) {
            DebugUtil.info("FC AWCHECK " + label + " error: " + e.getClass().getSimpleName());
            return false;
        }
    }

    private void protectSchedulerDeep(Object sched, String label) {
        DebugUtil.info("FC PROTECTDEEP protecting " + label + ": " + sched.getClass().getName());
        dumpFields(sched, label);
        protectInstance(sched);
        checkActiveWorkers(sched, label + "(after-instance)");
        Class<?> cl = sched.getClass();
        while (cl != null) {
            for (Field f : cl.getDeclaredFields()) {
                if (!BukkitScheduler.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object val = f.get(sched);
                    if (val != null && val != sched) {
                        protectSchedulerDeep(val, label + "." + f.getName());
                    }
                } catch (Exception ignored) {}
            }
            cl = cl.getSuperclass();
        }
    }

    private void dumpFields(Object obj, String label) {
        Class<?> cl = obj.getClass();
        while (cl != null) {
            for (Field f : cl.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(obj);
                    String vstr;
                    if (val == null) {
                        vstr = "null";
                    } else if (val instanceof java.util.Map<?, ?> || val instanceof java.util.Collection<?>) {
                        int sz = val instanceof java.util.Collection ? ((java.util.Collection<?>) val).size()
                            : ((java.util.Map<?, ?>) val).size();
                        vstr = val.getClass().getSimpleName() + "(size=" + sz + ")";
                    } else {
                        vstr = val.getClass().getSimpleName();
                    }
                    DebugUtil.info("FC FIELD " + label + " " + cl.getSimpleName() + "." + f.getName() + " [" + f.getType().getSimpleName() + "] = " + vstr);
                } catch (Exception ignored) {}
            }
            cl = cl.getSuperclass();
        }
    }

    private void protectSchedulerActiveWorkers() {
        try {
            Object sched = Bukkit.getScheduler();
            DebugUtil.info("FC PROTECT sched=" + sched.getClass().getName());
            Class<?> cl = sched.getClass();
            boolean found = false;
            while (cl != null) {
                try {
                    Field f = cl.getDeclaredField("activeWorkers");
                    f.setAccessible(true);
                    Object val = f.get(sched);
                    DebugUtil.info("FC PROTECT field activeWorkers on " + cl.getSimpleName() + " val=" + val);
                    if (val == null) {
                        f.set(sched, Collections.emptyList());
                        DebugUtil.info("FC PROTECT Set activeWorkers on " + cl.getSimpleName() + " OK");
                    }
                    found = true;
                    return;
                } catch (NoSuchFieldException ignored) {}
                cl = cl.getSuperclass();
            }
            DebugUtil.info("FC PROTECT no activeWorkers field found in hierarchy. Trying getActiveWorkers() directly...");
            try {
                java.lang.reflect.Method m = sched.getClass().getMethod("getActiveWorkers");
                Object result = m.invoke(sched);
                DebugUtil.info("FC PROTECT getActiveWorkers() returned: " + result);
            } catch (Exception e2) {
                DebugUtil.info("FC PROTECT getActiveWorkers() reflection error: " + e2.getClass().getSimpleName() + ": " + e2.getMessage());
            }
        } catch (Exception e) {
            DebugUtil.info("FC PROTECT error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void protectOriginalScheduler() {
        if (originalScheduler == null) return;
        String cn = originalScheduler.getClass().getName();
        DebugUtil.info("FC SHUTDOWN protecting original scheduler: " + cn);
        protectSchedulerDeep(originalScheduler, "original");
        protectSchedulerActiveWorkers();
        protectCurrentSchedulerField();
    }

    private void protectCurrentSchedulerField() {
        try {
            Server server = Bukkit.getServer();
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) getUnsafe();
            if (unsafe == null) { DebugUtil.info("FC SCHEDREAL no Unsafe"); return; }
            Class<?> cl = server.getClass();
            while (cl != null) {
                for (Field f : cl.getDeclaredFields()) {
                    if (BukkitScheduler.class.isAssignableFrom(f.getType())) {
                        long offset = unsafe.objectFieldOffset(f);
                        Object realValue = unsafe.getObject(server, offset);
                        if (realValue != null && !(realValue instanceof FoliaBukkitScheduler)) {
                            DebugUtil.info("FC SCHEDREAL real field value is " + realValue.getClass().getName() + " despite Bukkit.getScheduler() returning FoliaBukkitScheduler — protecting");
                            protectSchedulerDeep(realValue, "hiddenScheduler");
                        } else {
                            DebugUtil.info("FC SCHEDREAL field is " + (realValue == null ? "null" : "FoliaBukkitScheduler"));
                        }
                        return;
                    }
                }
                cl = cl.getSuperclass();
            }
        } catch (Exception e) {
            DebugUtil.info("FC SCHEDREAL error: " + e.getClass().getSimpleName());
        }
    }

    private static final String MODRINTH_PROJECT_ID = "oRLhVu0B";

    @SuppressWarnings("ExceptionSwallowDetector")
    private void checkForUpdates() {
        try {
            if (foliaConfig == null || !foliaConfig.isModrinthUpdateCheck()) return;
            String ver = getPluginMeta().getVersion();
            ModrinthUpdateChecker.check(MODRINTH_PROJECT_ID, ver, getLogger()).thenAccept(result -> {
                try {
                    if (result.hasUpdate) {
                        getLogger().warning("A new version is available: " + result.latestVersion
                            + (result.downloadUrl != null ? " — " + result.downloadUrl : ""));
                    }
                } catch (Throwable t) {
                    handleError("checkForUpdates callback", t);
                }
            });
        } catch (Throwable t) {
            handleError("checkForUpdates", t);
        }
    }

    List<ManagedPlugin> getLoadedPlugins() { return loadedPlugins; }

    ManagedPlugin findManagedPlugin(String name) {
        if (name == null || name.isEmpty()) return null;
        for (ManagedPlugin mp : loadedPlugins) {
            if (mp == null) continue;
            if (name.equalsIgnoreCase(mp.getName())) return mp;
        }
        return null;
    }

    void addLoadedPlugin(ManagedPlugin mp) {
        if (mp != null) loadedPlugins.add(mp);
    }

    void removeLoadedPlugin(ManagedPlugin mp) {
        loadedPlugins.remove(mp);
    }
}
