package xyz.vprolabs.foliacompat;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class FoliaConfig {

    private static final int CONFIG_VERSION = 4;

    private final File configFile;
    private final File dataFolder;
    private FileConfiguration config;

    private boolean resetCacheOnRestart;
    private boolean errorReportingEnabled;
    private boolean debug;
    private boolean modrinthUpdateCheck;

    public FoliaConfig(File dataFolder) {
        if (dataFolder == null) throw new IllegalArgumentException("dataFolder must not be null");
        this.dataFolder = dataFolder;
        this.configFile = new File(dataFolder, "config.yml");
    }

    public void load() {
        if (!configFile.exists()) {
            if (!dataFolder.mkdirs()) {
                FoliaPluginLoader.log.warning("Could not create data directory: " + dataFolder);
            }
            saveDefaultConfig();
        }

        config = new YamlConfiguration();
        try {
            config.load(configFile);
        } catch (IOException | InvalidConfigurationException e) {
            FoliaPluginLoader.log.warning("Failed to load config.yml: " + e.getMessage() + " — using defaults");
            config = new YamlConfiguration();
        }

        int fileVersion = config.getInt("config-version", 0);
        if (fileVersion < 0) {
            FoliaPluginLoader.log.warning("config-version is negative (" + fileVersion + "), treating as 0");
            fileVersion = 0;
        }
        if (fileVersion < CONFIG_VERSION) {
            mergeDefaults(fileVersion);
        }

        resetCacheOnRestart = config.getBoolean("reset-cache-on-restart", false);
        errorReportingEnabled = config.getBoolean("error-reporting.enabled", true);
        debug = config.getBoolean("debug", false);
        modrinthUpdateCheck = config.getBoolean("modrinth-update-check", true);
    }

    private void mergeDefaults(int oldVersion) {
        YamlConfiguration defaults = new YamlConfiguration();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            if (in == null) {
                FoliaPluginLoader.log.warning("Default config.yml not found in jar — skipping merge");
                return;
            }
            try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                defaults.load(reader);
            }
        } catch (IOException e) {
            FoliaPluginLoader.log.warning("Could not load default config.yml for merge: " + e.getMessage());
            return;
        } catch (InvalidConfigurationException e) {
            FoliaPluginLoader.log.warning("Default config.yml is malformed: " + e.getMessage());
            return;
        }

        for (String key : defaults.getKeys(true)) {
            if (!config.contains(key)) {
                config.set(key, defaults.get(key));
            }
        }
        config.set("config-version", CONFIG_VERSION);

        try {
            config.save(configFile);
            DebugUtil.info("Config updated from v" + oldVersion + " to v" + CONFIG_VERSION);
        } catch (IOException e) {
            FoliaPluginLoader.log.warning("Failed to save merged config: " + e.getMessage());
        }
    }

    private void saveDefaultConfig() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            if (in == null) {
                FoliaPluginLoader.log.warning("Default config.yml not found in jar");
                return;
            }
            java.nio.file.Files.copy(in, configFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            FoliaPluginLoader.log.warning("Failed to save default config: " + e.getMessage());
        }
    }

    public boolean isResetCacheOnRestart() {
        return resetCacheOnRestart;
    }

    public boolean isErrorReportingEnabled() {
        return errorReportingEnabled;
    }

    public boolean isDebug() {
        return debug;
    }

    public boolean isModrinthUpdateCheck() {
        return modrinthUpdateCheck;
    }
}
