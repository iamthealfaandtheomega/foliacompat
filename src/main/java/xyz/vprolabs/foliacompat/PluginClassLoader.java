package xyz.vprolabs.foliacompat;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import sun.misc.Unsafe;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public final class PluginClassLoader extends URLClassLoader {
    private static final Logger log = Logger.getLogger("FoliaCompat");
    PluginClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        try {
            return super.loadClass(name, resolve);
        } catch (ClassNotFoundException e) {
            if (getParent() != null) {
                try { return Class.forName(name, resolve, getParent()); } catch (Throwable ignored) {}
            }
            if (name.startsWith("net.minecraft.")) {
                String mojangName = resolveNmsRedirect(name);
                if (mojangName != null) {
                    try { return Class.forName(mojangName, resolve, getParent()); } catch (Exception ex) {
                        DebugUtil.info("FC REMAPFAIL " + name + " -> " + mojangName + ": " + ex.getClass().getSimpleName());
                    }
                }
            }
            throw e;
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        if (name.startsWith("org.bukkit.craftbukkit.")) {
            Class<?> resolved = resolveCraftBukkitRedirect(name);
            if (resolved != null) return resolved;
            return defineSyntheticCraftBukkitClass(name);
        }
        String path = name.replace('.', '/') + ".class";
        URL resource = findResource(path);
        if (resource == null) {
            throw new ClassNotFoundException(name);
        }
        try (InputStream is = resource.openStream()) {
            byte[] bytes = is.readAllBytes();
            if (FoliaPluginLoader.cachedBytecodeModifier != null && FoliaPluginLoader.cachedBytecodeModifyMethod != null) {
                try {
                    bytes = (byte[]) FoliaPluginLoader.cachedBytecodeModifyMethod.invoke(
                            FoliaPluginLoader.cachedBytecodeModifier, this, bytes);
                } catch (InvocationTargetException | IllegalAccessException ignored) {}
            }
            bytes = BytecodePatcher.patchBytecode(bytes, ClassMapper.spigotToMojangMap, ClassMapper.craftBukkitRenameMap);
            return defineClass(name, bytes, 0, bytes.length);
        } catch (IOException e) {
            throw new ClassNotFoundException(name, e);
        }
    }

    private final Set<String> defining = ConcurrentHashMap.<String>newKeySet();

    private Class<?> defineSyntheticCraftBukkitClass(String name) throws ClassNotFoundException {
        if (!defining.add(name)) {
            throw new ClassNotFoundException("Circular definition: " + name);
        }
        try {
            String pkg = name.substring(0, name.lastIndexOf('.') + 1);
            String simpleName = name.substring(name.lastIndexOf('.') + 1);
            String parentName = getEntityParent(simpleName, pkg);
            if (parentName != null && !parentName.equals("java.lang.Object")) {
                try {
                    loadClass(parentName);
                } catch (ClassNotFoundException e) {
                    defineSyntheticCraftBukkitClass(parentName);
                }
            }
            String superInternal = parentName != null ? parentName.replace('.', '/') : "java/lang/Object";
            byte[] bytes = CraftBukkitMapper.generateSyntheticBytes(name.replace('.', '/'), superInternal);
            Class<?> synth = defineClass(name, bytes, 0, bytes.length);
            DebugUtil.info("FC SYNTH defined " + name + " extends " + superInternal + " (" + bytes.length + " bytes)");
            return synth;
        } catch (Exception e) {
            throw new ClassNotFoundException("Cannot synthesize " + name + ": " + e.getClass().getSimpleName() + " - " + e.getMessage(), e);
        } finally {
            defining.remove(name);
        }
    }

    private static String getEntityParent(String simpleName, String pkg) {
        return switch (simpleName) {
            case "CraftEntity" -> null;
            case "CraftLivingEntity" -> pkg + "CraftEntity";
            case "CraftCreature" -> pkg + "CraftLivingEntity";
            case "CraftMonster" -> pkg + "CraftCreature";
            case "CraftHumanEntity" -> pkg + "CraftEntity";
            case "CraftPlayer" -> pkg + "CraftHumanEntity";
            case "CraftAnimal" -> pkg + "CraftEntity";
            case "CraftWaterAnimal" -> pkg + "CraftCreature";
            case "CraftAmbient" -> pkg + "CraftCreature";
            case "CraftFlyable" -> pkg + "CraftCreature";
            default -> "java.lang.Object";
        };
    }

    private static String resolveNmsRedirect(String name) {
        if (ClassMapper.cachedIMappingFile != null) {
            String r = ClassMapper.mapClassName(name);
            if (r != null && !r.equals(name.replace('.', '/'))) return r.replace('/', '.');
        }
        String mojangName = ClassMapper.mapViaObfHelper(name);
        if (mojangName == null) mojangName = ClassMapper.mapViaMappingFile(name);
        return mojangName;
    }

    private static Class<?> resolveCraftBukkitRedirect(String name) {
        String mapped = ClassMapper.craftBukkitRenameMap.get(name);
        if (mapped == null) return null;
        Class<?> cached = ClassMapper.craftBukkitRedirectClass;
        if (cached != null) {
            DebugUtil.info("FC CBLOADOK " + name + " -> " + mapped + " (cached)");
            return cached;
        }
        DebugUtil.info("FC CBLOADREDIR " + name + " -> " + mapped + " (no cache, trying classloaders)");
        ClassLoader[] cbs = {
            ClassMapper.class.getClassLoader(),
            Bukkit.class.getClassLoader(),
            ClassLoader.getSystemClassLoader(),
            getServerClassLoader(),
            getNmsServerClassLoader()
        };
        ClassLoader lastFailed = null;
        for (ClassLoader cl : cbs) {
            if (cl == null) continue;
            lastFailed = cl;
            try {
                Class<?> result = Class.forName(mapped, false, cl);
                DebugUtil.info("FC CBLOADOK " + name + " -> " + mapped + " via " + cl.getClass().getName());
                ClassMapper.craftBukkitRedirectClass = result;
                return result;
            } catch (Exception ex) {
                DebugUtil.info("FC CBLOADTRY " + cl.getClass().getName() + ": " + ex.getClass().getSimpleName());
            }
        }
        DebugUtil.info("FC CBLOADFAIL " + name + " -> " + mapped + " — last tried: " + (lastFailed != null ? lastFailed.getClass().getName() : "none"));
        return null;
    }

    private static ClassLoader getServerClassLoader() {
        try {
            return Bukkit.getServer().getClass().getClassLoader();
        } catch (Exception e) {
            return null;
        }
    }

    private static ClassLoader getNmsServerClassLoader() {
        try {
            Class<?> mcServer = Class.forName("net.minecraft.server.MinecraftServer");
            return mcServer.getClassLoader();
        } catch (Exception e) {
            return null;
        }
    }

    public static JavaPlugin loadPlugin(File jarFile, String jarName, PluginDescriptionFile desc)
            throws IllegalArgumentException, SecurityException, InvocationTargetException {
        if (jarFile == null || desc == null) throw new IllegalArgumentException("jarFile and desc must not be null");
        String mainClass = desc.getMain();
        if (mainClass == null || mainClass.isEmpty()) throw new IllegalArgumentException("No main class for " + jarName);
        String pluginName = desc.getName();
        if (pluginName == null || pluginName.isEmpty()) pluginName = jarFile.getName().replaceAll("\\.jar$", "");
        ClassLoader serverLoader = Bukkit.getServer().getClass().getClassLoader();
        ClassLoader parent = (serverLoader != null) ? serverLoader : ClassLoader.getSystemClassLoader();
        URL jarUrl;
        try { jarUrl = jarFile.toURI().toURL(); } catch (java.net.MalformedURLException e) {
            throw new IllegalArgumentException("Invalid jar path: " + jarFile, e);
        }

        PluginClassLoader cl = new PluginClassLoader(new URL[]{jarUrl}, parent);
        DebugUtil.info(jarName + ": created PluginClassLoader");

        try {
            Class<?> pluginClass = cl.loadClass(mainClass);
            if (Modifier.isAbstract(pluginClass.getModifiers())) {
                throw new IllegalStateException("Main class is abstract: " + pluginClass.getName());
            }
            DebugUtil.info(jarName + ": loaded main class " + pluginClass.getName());

            JavaPlugin plugin;
            try {
                plugin = (JavaPlugin) pluginClass.getDeclaredConstructor().newInstance();
                DebugUtil.info(jarName + ": instantiated via constructor");
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IllegalStateException ise && ise.getMessage() != null
                        && ise.getMessage().contains("valid classloader")) {
                    log.fine(jarName + ": constructor rejected classloader, using Unsafe.allocateInstance()");
                    plugin = unsafeAllocate(pluginClass);
                    initNullCollectionFields(plugin);
                } else { cl.close(); throw e; }
            } catch (NoSuchMethodException e) {
                log.fine(jarName + ": no no-arg constructor, using Unsafe.allocateInstance()");
                plugin = unsafeAllocate(pluginClass);
                initNullCollectionFields(plugin);
            }

            Server server = Bukkit.getServer();
            String safeName = FoliaPluginLoader.safePluginName(pluginName);
            initPluginFields(plugin, cl, server, desc,
                new File(Bukkit.getUpdateFolderFile().getParentFile(), safeName), jarFile, safeName, jarName);

            initStaticPluginField(plugin, pluginClass, jarName);
            postInitNullFields(plugin, pluginClass, jarName);
            DebugUtil.info(jarName + ": field-level init done");
            return plugin;
        } catch (InvocationTargetException | IllegalArgumentException | SecurityException | IllegalStateException e) {
            try { cl.close(); } catch (IOException suppressed) { e.addSuppressed(suppressed); }
            throw e;
        } catch (Exception e) {
            try { cl.close(); } catch (IOException suppressed) { e.addSuppressed(suppressed); }
            throw new RuntimeException("Failed to load plugin: " + jarName, e);
        }
    }

    private static JavaPlugin unsafeAllocate(Class<?> pluginClass)
            throws NoSuchFieldException, SecurityException, IllegalArgumentException,
                   IllegalStateException, IllegalAccessException, InstantiationException {
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        return (JavaPlugin) unsafe.allocateInstance(pluginClass);
    }

    private static void initPluginFields(JavaPlugin plugin, ClassLoader cl, Server server,
            PluginDescriptionFile desc, File dataFolder, File jarFile, String safeName, String jarName) {
        Class<?> walk = plugin.getClass();
        while (walk != null && walk != Object.class) {
            try {
                for (Field f : walk.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        switch (f.getName()) {
                            case "server" -> f.set(plugin, server);
                            case "description" -> f.set(plugin, desc);
                            case "pluginMeta" -> f.set(plugin, desc);
                            case "dataFolder" -> f.set(plugin, dataFolder);
                            case "file" -> f.set(plugin, jarFile);
                            case "classLoader" -> {
                                if (f.getType().isInstance(cl)) {
                                    f.set(plugin, cl);
                                } else {
                                    log.warning("FC CLFAIL " + jarName
                                        + ": cannot set classLoader (field type=" + f.getType().getName()
                                        + ", actual=" + cl.getClass().getName() + ")");
                                }
                            }
                            case "isEnabled" -> f.setBoolean(plugin, true);
                            case "logger" -> f.set(plugin, Logger.getLogger(safeName));
                            case "configFile" -> f.set(plugin, new File(dataFolder, "config.yml"));
                            case "config" -> {
                                if (f.getType() == File.class) {
                                    f.set(plugin, new File(dataFolder, "config.yml"));
                                }
                            }
                            case "loader" -> { if (FoliaPluginLoader.hostLoader != null) f.set(plugin, FoliaPluginLoader.hostLoader); }
                        }
                    } catch (Exception fieldErr) {
                        log.finest(jarName + ": skipped field " + f.getName() + ": " + fieldErr.getClass().getSimpleName());
                    }
                }
            } catch (Throwable t) {
                log.warning("FC FLDSCANFAIL " + jarName + "@" + walk.getSimpleName()
                    + ": " + t.getClass().getSimpleName() + " — " + t.getMessage());
                if (t instanceof VirtualMachineError) throw t;
            }
            walk = walk.getSuperclass();
        }
    }

    private static void initNullCollectionFields(JavaPlugin plugin) {
        Class<?> walk = plugin.getClass();
        while (walk != null && walk != Object.class) {
            try {
                for (java.lang.reflect.Field f : walk.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        Object val = f.get(plugin);
                        if (val != null) continue;
                        Class<?> type = f.getType();
                        if (type.isAssignableFrom(HashMap.class)) {
                            f.set(plugin, new HashMap<>());
                        } else if (type.isAssignableFrom(HashSet.class)) {
                            f.set(plugin, new HashSet<>());
                        } else if (type.isAssignableFrom(ArrayList.class)) {
                            f.set(plugin, new ArrayList<>());
                        }
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
            walk = walk.getSuperclass();
        }
    }

    private static void initStaticPluginField(JavaPlugin plugin, Class<?> pluginClass, String jarName) {
        Class<?> walk = pluginClass;
        while (walk != null && walk != Object.class) {
            try {
                for (java.lang.reflect.Field f : walk.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers())) continue;
                    if (!f.getType().isAssignableFrom(plugin.getClass())) continue;
                    try {
                        f.setAccessible(true);
                        if (f.get(null) == null) {
                            f.set(null, plugin);
                            DebugUtil.info(jarName + ": set static field " + walk.getSimpleName() + "." + f.getName());
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Throwable ignored) {}
            walk = walk.getSuperclass();
        }
    }

    private static void postInitNullFields(JavaPlugin plugin, Class<?> pluginClass, String jarName) {
        File df = plugin.getDataFolder();
        initNullFileFields(plugin, df);
        Class<?> walk = pluginClass;
        while (walk != null && walk != Object.class) {
            try {
                for (java.lang.reflect.Field f : walk.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        Object obj = f.get(null);
                        if (obj == null || obj == plugin) continue;
                        if (obj instanceof JavaPlugin) initNullFileFields((JavaPlugin) obj, df);
                        else initNullFileFields(obj, df);
                    } catch (Exception ignored) {}
                }
            } catch (Throwable ignored) {}
            walk = walk.getSuperclass();
        }
    }

    private static void initNullFileFields(Object target, File dataFolder) {
        Class<?> cl = target.getClass();
        while (cl != null && cl != Object.class) {
            try {
                for (java.lang.reflect.Field f : cl.getDeclaredFields()) {
                    try {
                        f.setAccessible(true);
                        Object val = f.get(target);
                        if (val != null) continue;
                        Class<?> type = f.getType();
                        if (type == File.class) {
                            f.set(target, dataFolder);
                        } else if (type.isAssignableFrom(HashMap.class)) {
                            f.set(target, new HashMap<>());
                        } else if (type.isAssignableFrom(HashSet.class)) {
                            f.set(target, new HashSet<>());
                        } else if (type.isAssignableFrom(ArrayList.class)) {
                            f.set(target, new ArrayList<>());
                        }
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
            cl = cl.getSuperclass();
        }
    }
}
