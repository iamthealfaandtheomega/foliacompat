package xyz.vprolabs.foliacompat;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginCommand;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.SimplePluginManager;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public final class PluginRegistrar {
    private static final Logger log = Logger.getLogger("FoliaCompat");

    private PluginRegistrar() {}

    @SuppressWarnings("unchecked")
    public static void registerPlugin(Plugin plugin) {
        if (plugin == null) { log.warning("registerPlugin: plugin is null"); return; }
        String name;
        try { name = plugin.getName(); } catch (Exception e) { name = plugin.getClass().getSimpleName(); }
        DebugUtil.info("Registering plugin: " + name);

        SimplePluginManager pm;
        try { pm = (SimplePluginManager) Bukkit.getPluginManager(); } catch (Exception e) {
            log.warning(name + ": cannot get PluginManager: " + e.getMessage());
            return;
        }

        try {
            Field f = SimplePluginManager.class.getDeclaredField("plugins");
            f.setAccessible(true);
            Object val = f.get(pm);
            if (val instanceof List<?> list) ((List<Plugin>) list).add(plugin);
        } catch (NoSuchFieldException | IllegalAccessException | SecurityException | IllegalArgumentException | IllegalStateException e) {
            log.warning(name + ": cannot add to plugins list: " + e.getMessage());
            return;
        }

        try {
            Field f = SimplePluginManager.class.getDeclaredField("lookupNames");
            f.setAccessible(true);
            Object val = f.get(pm);
            if (val instanceof Map<?, ?> map) ((Map<String, Plugin>) map).put(name, plugin);
        } catch (NoSuchFieldException | IllegalAccessException | SecurityException | IllegalArgumentException | IllegalStateException e) {
            log.warning(name + ": cannot add to lookupNames: " + e.getMessage());
        }

        try {
            Field f = SimplePluginManager.class.getDeclaredField("fileAssociations");
            f.setAccessible(true);
            Object val = f.get(pm);
            if (val instanceof Map<?, ?> map) ((Map<String, Plugin>) map).put(name, plugin);
        } catch (NoSuchFieldException e) {
            log.fine(name + ": fileAssociations not found, skipping");
        } catch (IllegalAccessException | SecurityException | IllegalArgumentException | IllegalStateException e) {
            log.warning(name + ": fileAssociations error: " + e.getMessage());
        }

        registerCommands(plugin, name);
        registerPermissions(plugin, name);
        DebugUtil.info(name + ": registered");
    }

    @SuppressWarnings("unchecked")
    private static void registerCommands(Plugin plugin, String name) {
        try {
            Server server = Bukkit.getServer();
            Field cf = null;
            Class<?> sc = server.getClass();
            while (sc != null) {
                try { cf = sc.getDeclaredField("commandMap"); break; }
                catch (NoSuchFieldException e) { sc = sc.getSuperclass(); }
            }
            if (cf == null) return;
            cf.setAccessible(true);
            Object val = cf.get(server);
            if (!(val instanceof CommandMap cm)) return;

            Map<String, Map<String, Object>> cmds = plugin.getDescription().getCommands();
            if (cmds == null || cmds.isEmpty()) return;

            Constructor<PluginCommand> ctor = PluginCommand.class.getDeclaredConstructor(String.class, Plugin.class);
            ctor.setAccessible(true);
            for (Map.Entry<String, Map<String, Object>> e : cmds.entrySet()) {
                try {
                    String cn = e.getKey();
                    PluginCommand pc = ctor.newInstance(cn, plugin);
                    Map<String, Object> def = e.getValue();
                    if (def.containsKey("description")) pc.setDescription((String) def.get("description"));
                    if (def.containsKey("aliases")) {
                        Object a = def.get("aliases");
                        if (a instanceof List) pc.setAliases((List<String>) a);
                        else if (a instanceof String) pc.setAliases(List.of((String) a));
                    }
                    if (def.containsKey("permission")) pc.setPermission((String) def.get("permission"));
                    cm.register(name, pc);
                } catch (InvocationTargetException | InstantiationException | IllegalAccessException | SecurityException
                        | IllegalArgumentException | IllegalStateException ce) {
                    log.warning(name + ": command error: " + ce.getMessage());
                }
            }
        } catch (IllegalAccessException | NoSuchMethodException | SecurityException | IllegalArgumentException | IllegalStateException e) {
            log.warning(name + ": command registration error: " + e.getMessage());
        }
    }

    private static void registerPermissions(Plugin plugin, String name) {
        try {
            for (Permission perm : plugin.getDescription().getPermissions()) {
                if (perm == null) continue;
                try { Bukkit.getPluginManager().addPermission(perm); } catch (IllegalArgumentException ignored) {}
            }
        } catch (SecurityException | IllegalArgumentException e) {
            log.fine(name + ": permission error: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    static void unregisterPlugin(Plugin plugin) {
        if (plugin == null) return;
        String name;
        try { name = plugin.getName(); } catch (Exception e) { name = plugin.getClass().getSimpleName(); }
        LogUtil.info("Unregistering plugin: " + name);
        try {
            SimplePluginManager pm = (SimplePluginManager) Bukkit.getPluginManager();
            Field pf = SimplePluginManager.class.getDeclaredField("plugins");
            pf.setAccessible(true);
            if (pf.get(pm) instanceof List<?> list) list.remove(plugin);
            Field lf = SimplePluginManager.class.getDeclaredField("lookupNames");
            lf.setAccessible(true);
            if (lf.get(pm) instanceof Map<?, ?> map) map.remove(name);
        } catch (NoSuchFieldException | IllegalAccessException | SecurityException | IllegalArgumentException e) {
            log.warning(name + ": unregister error: " + e.getMessage());
        }
    }

    static void copyToMainPlugins(File originalJar, File patchedJar) {
        if (originalJar == null || patchedJar == null) return;
        try {
            File mainDir = Bukkit.getUpdateFolderFile().getParentFile();
            if (mainDir == null || !mainDir.isDirectory()) return;
            File target = new File(mainDir, originalJar.getName());
            if (target.isFile()) return;
            Files.copy(patchedJar.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            DebugUtil.info("Copied patched " + originalJar.getName() + " to " + target.getParent()
                    + " — will load natively on next restart");
        } catch (IOException | SecurityException | IllegalArgumentException e) {
            log.fine("Could not copy to plugins dir: " + e.getMessage());
        }
    }
}
