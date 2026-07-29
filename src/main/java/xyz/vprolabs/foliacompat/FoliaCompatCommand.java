package xyz.vprolabs.foliacompat;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

public class FoliaCompatCommand implements TabExecutor {

    private static final Logger log = Logger.getLogger("FoliaCompat");
    private final FoliaCompat foliaCompat;
    private final File pluginDir;

    private static final List<String> SUBCOMMANDS = Arrays.asList("load", "unload", "reload", "list");

    public FoliaCompatCommand(FoliaCompat foliaCompat, File pluginDir) {
        this.foliaCompat = foliaCompat;
        this.pluginDir = pluginDir;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("foliacompat.admin")) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("§6Usage: /" + label + " <load|unload|reload|list> [name]");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "list" -> handleList(sender);
            case "load" -> handleLoad(sender, args);
            case "unload" -> handleUnload(sender, args);
            case "reload" -> handleReload(sender, args);
            default -> sender.sendMessage("§cUnknown subcommand. Use: load, unload, reload, list");
        }
        return true;
    }

    private void handleList(CommandSender sender) {
        List<ManagedPlugin> plugins = foliaCompat.getLoadedPlugins();
        if (plugins.isEmpty()) {
            sender.sendMessage("§eNo plugins loaded via FoliaCompat.");
            return;
        }
        sender.sendMessage("§6FoliaCompat loaded plugins (" + plugins.size() + "):");
        for (ManagedPlugin mp : plugins) {
            if (mp == null) continue;
            String pname = mp.getName() != null ? mp.getName() : "?";
            String status = mp.isEnabled() ? "§aENABLED" : "§cDISABLED";
            sender.sendMessage("  §7- §f" + pname + " §7" + status);
        }
    }

    private void handleLoad(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /fc load <filename.jar>");
            return;
        }
        String fileName = args[1];
        if (!fileName.endsWith(".jar")) fileName += ".jar";
        File jarFile = new File(pluginDir, fileName);
        if (!jarFile.isFile()) {
            sender.sendMessage("§cFile not found: " + jarFile.getAbsolutePath());
            return;
        }
        String jarName = jarFile.getName();
        String pluginName = FoliaPluginLoader.readPluginName(jarFile);
        if (pluginName == null) {
            sender.sendMessage("§cCannot read plugin.yml from " + jarName);
            return;
        }
        if (Bukkit.getPluginManager().getPlugin(pluginName) != null) {
            sender.sendMessage("§cPlugin '" + pluginName + "' is already loaded. Unload it first.");
            return;
        }
        if (foliaCompat.findManagedPlugin(pluginName) != null) {
            sender.sendMessage("§cPlugin '" + pluginName + "' is already managed by FoliaCompat. Unload it first.");
            return;
        }
        sender.sendMessage("§6Loading " + jarName + "...");
        try {
            Plugin plugin = FoliaPluginLoader.loadPlugin(jarFile, jarName);
            if (plugin == null) {
                sender.sendMessage("§cFailed to load " + jarName + " — loadPlugin returned null");
                return;
            }
            ClassLoader cl = plugin.getClass().getClassLoader();
            ManagedPlugin mp = new ManagedPlugin(plugin, cl);
            FoliaPluginLoader.registerPlugin(plugin);
            FoliaPluginLoader.callWithTCCL(() -> { plugin.onLoad(); return null; }, cl);
            mp.setEnabled(true);
            foliaCompat.addLoadedPlugin(mp);
            FoliaPluginLoader.callWithTCCL(() -> { plugin.onEnable(); return null; }, cl);
            sender.sendMessage("§aLoaded and enabled " + pluginName + " v" + plugin.getPluginMeta().getVersion());
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            sender.sendMessage("§cFailed to load " + jarName + ": " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
            log.warning("FC CMD load failed for " + jarName + ": " + cause.getMessage());
        }
    }

    private void handleUnload(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /fc unload <pluginname>");
            return;
        }
        String pname = args[1];
        ManagedPlugin mp = foliaCompat.findManagedPlugin(pname);
        if (mp == null) {
            sender.sendMessage("§cPlugin '" + pname + "' is not managed by FoliaCompat.");
            return;
        }
        Plugin plugin = mp.plugin();
        if (plugin == null) {
            sender.sendMessage("§cPlugin " + pname + " has null plugin instance.");
            return;
        }
        sender.sendMessage("§6Unloading " + pname + "...");
        try {
            mp.setEnabled(false);
            FoliaPluginLoader.callWithTCCL(() -> { plugin.onDisable(); return null; }, mp.classLoader());
        } catch (Exception e) {
            log.warning("FC CMD unload disable error for " + pname + ": " + e.getMessage());
        }
        PluginRegistrar.unregisterPlugin(plugin);
        mp.cleanup();
        foliaCompat.removeLoadedPlugin(mp);
        sender.sendMessage("§aUnloaded " + pname);
    }

    private void handleReload(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /fc reload <pluginname>");
            return;
        }
        String pname = args[1];
        ManagedPlugin mp = foliaCompat.findManagedPlugin(pname);
        if (mp == null) {
            sender.sendMessage("§cPlugin '" + pname + "' is not managed by FoliaCompat.");
            return;
        }
        Plugin plugin = mp.plugin();
        if (plugin == null) {
            sender.sendMessage("§cPlugin " + pname + " has null plugin instance.");
            return;
        }
        String jarName = pname + ".jar";
        File jarFile = new File(pluginDir, jarName);
        if (!jarFile.isFile()) {
            sender.sendMessage("§cCannot find jar for " + pname + " (expected: " + jarFile.getAbsolutePath() + ")");
            return;
        }
        sender.sendMessage("§6Reloading " + pname + "...");
        try { mp.setEnabled(false); FoliaPluginLoader.callWithTCCL(() -> { plugin.onDisable(); return null; }, mp.classLoader()); } catch (Exception e) { log.fine("FC CMD reload disable error: " + e.getMessage()); }
        PluginRegistrar.unregisterPlugin(plugin);
        mp.cleanup();
        foliaCompat.removeLoadedPlugin(mp);
        try {
            Plugin newPlugin = FoliaPluginLoader.loadPlugin(jarFile, jarName);
            if (newPlugin == null) {
                sender.sendMessage("§cFailed to reload " + pname + " — loadPlugin returned null");
                return;
            }
            ClassLoader newCl = newPlugin.getClass().getClassLoader();
            ManagedPlugin newMp = new ManagedPlugin(newPlugin, newCl);
            FoliaPluginLoader.registerPlugin(newPlugin);
            FoliaPluginLoader.callWithTCCL(() -> { newPlugin.onLoad(); return null; }, newCl);
            newMp.setEnabled(true);
            foliaCompat.addLoadedPlugin(newMp);
            FoliaPluginLoader.callWithTCCL(() -> { newPlugin.onEnable(); return null; }, newCl);
            sender.sendMessage("§aReloaded " + pname + " v" + newPlugin.getPluginMeta().getVersion());
        } catch (Exception e) {
            sender.sendMessage("§cFailed to reload " + pname + ": " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("foliacompat.admin")) return List.of();
        if (args.length == 1) {
            return SUBCOMMANDS.stream()
                .filter(s -> s.startsWith(args[0].toLowerCase()))
                .collect(java.util.stream.Collectors.toList());
        }
        if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "load" -> {
                    List<String> jars = new ArrayList<>();
                    if (pluginDir.isDirectory()) {
                        File[] files = pluginDir.listFiles((d, n) -> n.endsWith(".jar"));
                        if (files != null) for (File f : files) jars.add(f.getName());
                    }
                    return jars.stream().filter(s -> s.startsWith(args[1])).collect(java.util.stream.Collectors.toList());
                }
                case "unload", "reload" -> {
                    return foliaCompat.getLoadedPlugins().stream()
                        .filter(mp -> mp != null && mp.getName() != null)
                        .map(ManagedPlugin::getName)
                        .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(java.util.stream.Collectors.toList());
                }
            }
        }
        return List.of();
    }
}
