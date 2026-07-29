package xyz.vprolabs.foliacompat;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class FoliaPluginCache {

    private final File cacheDir;
    private final File metadataFile;
    private boolean dirty;
    private static String currentFcVersion;

    public FoliaPluginCache(File cacheDir) {
        if (cacheDir == null) {
            try {
                this.cacheDir = Files.createTempDirectory("foliacompat-cache").toFile();
            } catch (IOException e) {
                throw new RuntimeException("Cannot create temp cache directory", e);
            }
        } else {
            this.cacheDir = cacheDir;
        }
        this.metadataFile = new File(this.cacheDir, "cache-metadata.json");
    }

    public static void setCurrentFcVersion(String version) {
        currentFcVersion = version;
    }

    public void init(boolean reset) throws IOException {
        try {
            if (reset && cacheDir.exists()) {
                try (var walk = Files.walk(cacheDir.toPath())) {
                    walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); }
                            catch (IOException ignored) {}
                        });
                }
                DebugUtil.info("Plugin cache cleared (reset-cache-on-restart=true)");
            }
            cacheDir.mkdirs();
        } catch (DirectoryIteratorException e) {
            throw new IOException("Error walking cache directory", e.getCause());
        } catch (SecurityException e) {
            throw new IOException("Access denied to cache directory", e);
        }
    }

    public File getCachedJar(File originalJar) {
        if (originalJar == null) return null;
        String hash = computeHash(originalJar);
        if (hash == null) return null;

        String name = originalJar.getName().replaceAll("\\.jar$", "");
        File cached = new File(cacheDir, name + "-" + hash + ".jar");
        if (cached.isFile()) {
            if (isCacheVersionValid(cached)) {
                return cached;
            }
            DebugUtil.info("FC CACHEMISS " + cached.getName() + " (version mismatch)");
            try { Files.deleteIfExists(cached.toPath()); } catch (IOException ignored) {}
            return null;
        }

        File legacy = new File(cacheDir, name + ".jar");
        if (legacy.isFile()) {
            if (isCacheVersionValid(legacy)) {
                return legacy;
            }
            DebugUtil.info("FC CACHEMISS " + legacy.getName() + " (version mismatch)");
            try { Files.deleteIfExists(legacy.toPath()); } catch (IOException ignored) {}
            return null;
        }

        return null;
    }

    private boolean isCacheVersionValid(File cachedJar) {
        if (currentFcVersion == null || currentFcVersion.isEmpty()) return true;
        try (JarFile jf = new JarFile(cachedJar)) {
            JarEntry entry = jf.getJarEntry("FoliaCompat.txt");
            if (entry == null) return false;
            byte[] data = jf.getInputStream(entry).readAllBytes();
            String content = new String(data, StandardCharsets.UTF_8);
            return content.contains("FoliaCompat v" + currentFcVersion);
        } catch (IOException e) {
            return false;
        } catch (SecurityException e) {
            return false;
        }
    }

    public File createCachedJar(File originalJar) throws IOException {
        if (originalJar == null) throw new NullPointerException("originalJar cannot be null");
        String hash = computeHash(originalJar);
        if (hash == null) {
            throw new IOException("Cannot compute hash for " + originalJar.getName());
        }

        String name = originalJar.getName().replaceAll("\\.jar$", "");
        File cached = new File(cacheDir, name + "-" + hash + ".jar");
        JarPatcher.patchJar(originalJar, cached);
        DebugUtil.info("Cached patched jar: " + cached.getName());
        return cached;
    }

    public void clear() throws IOException {
        if (cacheDir.exists()) {
            try (var walk = Files.walk(cacheDir.toPath())) {
                walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); }
                        catch (IOException ignored) {}
                    });
            } catch (DirectoryIteratorException e) {
                throw new IOException("Error walking cache directory during clear", e.getCause());
            }
        }
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static String computeHash(File file) {
        if (file == null) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[32768];
            try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
                int read;
                while ((read = in.read(buf)) != -1) {
                    md.update(buf, 0, read);
                }
            }
            byte[] hash = md.digest();
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) {
                int v = b & 0xff;
                hex.append(HEX[v >>> 4]).append(HEX[v & 0xf]);
            }
            return hex.substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            FoliaPluginLoader.log.warning("FC HASHERR SHA-256 not available: " + e.getMessage());
            return null;
        } catch (IOException e) {
            FoliaPluginLoader.log.warning("FC HASHERR io " + file.getName() + ": " + e.getMessage());
            return null;
        } catch (SecurityException e) {
            FoliaPluginLoader.log.warning("FC HASHERR access " + file.getName() + ": " + e.getMessage());
            return null;
        }
    }
}
