package xyz.vprolabs.foliacompat;

import org.bukkit.plugin.Plugin;

import java.io.Closeable;
import java.io.IOException;

public class ManagedPlugin {

    private final Plugin plugin;
    private final ClassLoader classLoader;
    private boolean enabled;

    public ManagedPlugin(Plugin plugin, ClassLoader classLoader) {
        if (plugin == null) throw new NullPointerException("plugin cannot be null");
        this.plugin = plugin;
        this.classLoader = classLoader;
        this.enabled = false;
    }

    public Plugin plugin() {
        return plugin;
    }

    public ClassLoader classLoader() {
        return classLoader;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getName() {
        try {
            return plugin.getName();
        } catch (RuntimeException | Error e) {
            return fallbackName();
        }
    }

    private String fallbackName() {
        try {
            return plugin.getClass().getSimpleName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    public void cleanup() {
        if (classLoader instanceof Closeable c) {
            try {
                c.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }
}
