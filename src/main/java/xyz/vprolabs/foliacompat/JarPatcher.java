package xyz.vprolabs.foliacompat;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipException;

public final class JarPatcher {

    private static final byte[] FOLIA_SUPPORTED_MARKER = "\nfolia-supported: true".getBytes(StandardCharsets.UTF_8);

    private JarPatcher() {}

    public static void patchJar(File inputJar, File outputJar) throws IOException {
        if (inputJar == null) throw new NullPointerException("inputJar");
        if (outputJar == null) throw new NullPointerException("outputJar");

        byte[] preloaded = null;
        if (inputJar.equals(outputJar)) {
            try (InputStream in = new FileInputStream(inputJar)) {
                preloaded = in.readAllBytes();
            }
        }

        try (JarInputStream jis = new JarInputStream(new BufferedInputStream(
                preloaded != null ? new ByteArrayInputStream(preloaded) : new FileInputStream(inputJar)))) {
            try (JarOutputStream jos = new JarOutputStream(new BufferedOutputStream(new FileOutputStream(outputJar)))) {
                byte[] buf = new byte[32768];
                JarEntry entry;
            boolean patched = false;

            while ((entry = jis.getNextJarEntry()) != null) {
                String name = entry.getName();

                if ("plugin.yml".equals(name) || "plugin.yaml".equals(name)) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    int read;
                    while ((read = jis.read(buf)) != -1) {
                        baos.write(buf, 0, read);
                    }
                    byte[] original = baos.toByteArray();

                    if (hasFoliaSupported(new ByteArrayInputStream(original))) {
                        jos.putNextEntry(new JarEntry(name));
                        jos.write(original);
                    } else {
                        byte[] patchedBytes = addFoliaSupported(original);
                        JarEntry patchedEntry = new JarEntry(name);
                        patchedEntry.setTime(entry.getTime());
                        jos.putNextEntry(patchedEntry);
                        jos.write(patchedBytes);
                        patched = true;
                    }
                } else {
                    jos.putNextEntry(new JarEntry(name));
                    if (entry.getSize() > 0) {
                        int read;
                        while ((read = jis.read(buf)) != -1) {
                            jos.write(buf, 0, read);
                        }
                    }
                }
                jos.closeEntry();
                jis.closeEntry();
            }

            JarEntry marker = new JarEntry("FoliaCompat.txt");
            marker.setTime(System.currentTimeMillis());
            jos.putNextEntry(marker);
            byte[] info = ("This jar was patched by FoliaCompat v" + FoliaPluginLoader.getVersion()
                    + "\nPatched at: " + java.time.Instant.now()
                    + "\nPurpose: Added 'folia-supported: true' to plugin.yml for Folia server compatibility."
                    + "\nOriginal jar: " + inputJar.getName()
                    + "\n").getBytes(StandardCharsets.UTF_8);
            jos.write(info);
            jos.closeEntry();
        } catch (ZipException e) {
            throw new IOException("Corrupted jar file: " + inputJar.getName(), e);
        } catch (SecurityException e) {
            throw new IOException("Access denied: " + e.getMessage(), e);
        }
        }
    }

    private static boolean hasFoliaSupported(InputStream in) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().startsWith("folia-supported:")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static byte[] addFoliaSupported(byte[] yaml) {
        byte[] trimmed = yaml;
        int len = yaml.length;

        while (len > 0 && (yaml[len - 1] == '\n' || yaml[len - 1] == '\r' || yaml[len - 1] == ' ')) {
            len--;
        }

        byte[] result = new byte[len + FOLIA_SUPPORTED_MARKER.length + 1];
        System.arraycopy(yaml, 0, result, 0, len);
        result[len] = '\n';
        System.arraycopy(FOLIA_SUPPORTED_MARKER, 0, result, len + 1, FOLIA_SUPPORTED_MARKER.length);
        return result;
    }
}
